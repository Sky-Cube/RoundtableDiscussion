package com.aipanel.service.debate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.aipanel.config.PanelProperties;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeContract;
import com.aipanel.gateway.RuntimeGateway;
import com.aipanel.gateway.RuntimeTask;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class RuntimeCalls {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeCalls.class);
    private static final long BID_GRACE_MILLIS = 350;
    private final RuntimeGateway gateway;
    private final RuntimeContract contract;
    private final ContentGuard guard;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;
    private final PanelProperties.Timeouts timeouts;

    public RuntimeCalls(RuntimeGateway gateway, RuntimeContract contract, ContentGuard guard,
            @Qualifier("modelExecutor") ThreadPoolTaskExecutor executor, Clock clock, PanelProperties properties) {
        this.gateway = gateway;
        this.contract = contract;
        this.guard = guard;
        this.executor = executor;
        this.clock = clock;
        this.timeouts = properties.gateway().timeouts();
    }

    public boolean fits(RuntimeTask task, Map<String, Object> input) {
        return gateway.estimate(task, input) <= gateway.inputLimit(task);
    }

    public <T> T generateRoster(Supplier<T> request) {
        // 阵容最多尝试两次（首次 + 一次修正），外层等待需覆盖两次单次超时。
        return await(submit(request::get), clock.instant().plusSeconds(timeouts.rosterSeconds() * 2L + 10));
    }

    public <T> T call(RuntimeTask task, Map<String, Object> input, DiscussionBudget budget,
            boolean inWindow, Instant deadline, Runnable onStart, Function<JsonNode, T> validator) {
        try {
            return await(submit(() -> {
                requireCurrent(deadline);
                onStart.run();
                return invoke(task, input, budget, inWindow, deadline, validator);
            }), deadline);
        } catch (ModelGatewayException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // 状态写入或校验器的意外异常只应终止本次调用，不能让整个讨论线程崩溃。
            LOG.warn("模型调用执行异常 task={} error={}", task, failure.getClass().getName());
            throw new ModelGatewayException(ModelGatewayException.Kind.UPSTREAM_ERROR, "模型调用执行异常", failure);
        }
    }

    /** 同一快照并发报名。首个可发言者就绪后短暂收集同窗竞争者，PASS 不触发早收窗。 */
    public <T> List<T> collect(List<Supplier<CompletableFuture<T>>> requests, Instant deadline,
            Predicate<T> canSpeak) {
        BlockingQueue<CompletableFuture<T>> completed = new LinkedBlockingQueue<>();
        List<Future<?>> active = new ArrayList<>();
        List<T> results = new ArrayList<>();
        Instant cutoff = deadline;
        int permanentFailures = 0;
        ModelGatewayException lastFailure = null;
        try {
            for (var request : requests) {
                try {
                    active.add(executor.submit(() -> {
                        try {
                            request.get().whenComplete((value, error) -> completed.add(error == null
                                    ? CompletableFuture.completedFuture(value) : CompletableFuture.failedFuture(error)));
                        } catch (RuntimeException error) {
                            completed.add(CompletableFuture.failedFuture(error));
                        }
                    }));
                } catch (RejectedExecutionException busy) {
                    completed.add(CompletableFuture.failedFuture(busy));
                }
            }
            for (int i = 0; i < requests.size(); i++) {
                long nanos = Duration.between(clock.instant(), cutoff).toNanos();
                var next = completed.poll(Math.max(0, nanos), TimeUnit.NANOSECONDS);
                if (next == null) {
                    break;
                }
                try {
                    T result = next.join();
                    results.add(result);
                    if (canSpeak.test(result)) {
                        cutoff = earlier(cutoff, clock.instant().plusMillis(BID_GRACE_MILLIS));
                    }
                } catch (CompletionException failure) {
                    if (failure.getCause() instanceof ModelGatewayException model
                            && !model.repairable() && model.kind() != ModelGatewayException.Kind.STALE_RESPONSE) {
                        permanentFailures++;
                        lastFailure = model;
                    }
                }
            }
            if (lastFailure != null && permanentFailures == requests.size()) {
                throw lastFailure;
            }
            return List.copyOf(results);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(ModelGatewayException.Kind.STALE_RESPONSE, "报名窗口已取消");
        } finally {
            active.forEach(future -> future.cancel(true));
        }
    }

    public <T> T invoke(RuntimeTask task, Map<String, Object> input, DiscussionBudget budget,
            boolean inWindow, Instant deadline, Function<JsonNode, T> validator) {
        List<String> codes = List.of();
        if (!fits(task, input)) {
            throw new ModelGatewayException(ModelGatewayException.Kind.CONTEXT_UNAVAILABLE, "模型上下文超限");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            requireCurrent(deadline);
            if (!budget.acquire(task == RuntimeTask.R07, inWindow && attempt == 0)) {
                throw ModelGatewayException.timeout();
            }
            try {
                // 提炼需要逐条核对全部实录和引文，仍受全场收尾截止时间约束。
                int seconds = switch (task) {
                    case R04 -> timeouts.insightSeconds();
                    case R07 -> timeouts.summarySeconds();
                    default -> timeouts.utteranceSeconds();
                };
                Instant physicalDeadline = earlier(deadline, clock.instant().plusSeconds(seconds));
                JsonNode output = contract.parse(task, gateway.generate(task, input, codes, physicalDeadline));
                requireTimely(physicalDeadline, deadline);
                guard.checkOutput(output);
                T result = validator.apply(output);
                requireTimely(physicalDeadline, deadline);
                return result;
            } catch (ModelGatewayException failure) {
                LOG.info("模型任务未通过 task={} attempt={} kind={}", task, attempt + 1, failure.kind());
                if (attempt == 1 || !failure.repairable()) {
                    throw failure;
                }
                codes = failure.kind() == ModelGatewayException.Kind.FORMAT_INVALID
                        ? List.of(failure.getMessage()) : List.of();
            } catch (RuntimeException failure) {
                // 模型输出已通过 Schema，业务校验仍出现意外异常时按上游故障隔离本次任务。
                LOG.warn("模型任务执行异常 task={} attempt={} error={}", task, attempt + 1, failure.getClass().getName());
                throw new ModelGatewayException(ModelGatewayException.Kind.UPSTREAM_ERROR, "模型任务执行异常", failure);
            }
        }
        throw new IllegalStateException("模型尝试次数异常");
    }

    public static ModelGatewayException invalid(String code) {
        return new ModelGatewayException(ModelGatewayException.Kind.FORMAT_INVALID, code);
    }

    public static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private <T> Future<T> submit(Callable<T> request) {
        try {
            return executor.submit(request);
        } catch (RejectedExecutionException error) {
            throw new ModelGatewayException(ModelGatewayException.Kind.REJECTED, "模型工作池已满");
        }
    }

    private <T> T await(Future<T> pending, Instant deadline) {
        try {
            long remaining = Duration.between(clock.instant(), deadline).toNanos();
            if (pending.isDone()) {
                return pending.get();
            }
            if (remaining <= 0) {
                throw ModelGatewayException.timeout();
            }
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("模型工作任务异常", error.getCause());
        } catch (TimeoutException error) {
            throw ModelGatewayException.timeout();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(ModelGatewayException.Kind.STALE_RESPONSE, "模型工作已取消");
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private void requireCurrent(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) {
            throw new ModelGatewayException(ModelGatewayException.Kind.STALE_RESPONSE, "模型结果已过期");
        }
    }

    private void requireTimely(Instant physicalDeadline, Instant logicalDeadline) {
        requireCurrent(logicalDeadline);
        if (!clock.instant().isBefore(physicalDeadline)) {
            throw ModelGatewayException.timeout();
        }
    }
}
