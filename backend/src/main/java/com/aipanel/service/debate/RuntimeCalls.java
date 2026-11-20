package com.aipanel.service.debate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeContract;
import com.aipanel.gateway.RuntimeGateway;
import com.aipanel.gateway.RuntimeTask;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class RuntimeCalls {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeCalls.class);
    private final RuntimeGateway gateway;
    private final RuntimeContract contract;
    private final ContentGuard guard;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;

    public RuntimeCalls(RuntimeGateway gateway, RuntimeContract contract, ContentGuard guard,
            @Qualifier("modelExecutor") ThreadPoolTaskExecutor executor, Clock clock) {
        this.gateway = gateway;
        this.contract = contract;
        this.guard = guard;
        this.executor = executor;
        this.clock = clock;
    }

    public boolean fits(RuntimeTask task, Map<String, Object> input) {
        return gateway.estimate(task, input) <= gateway.inputLimit(task);
    }

    public <T> T generateRoster(Supplier<T> request) {
        return await(submit(request::get), clock.instant().plusSeconds(42));
    }

    public <T> T call(RuntimeTask task, Map<String, Object> input, DiscussionBudget budget,
            boolean inWindow, Instant deadline, Runnable onStart, Function<JsonNode, T> validator) {
        return await(submit(() -> {
            requireCurrent(deadline);
            onStart.run();
            return invoke(task, input, budget, inWindow, deadline, validator);
        }), deadline);
    }

    public <T> List<T> collect(List<Supplier<T>> requests, Instant deadline) {
        List<T> results = new ArrayList<>();
        List<Future<T>> active = new ArrayList<>(requests.size());
        try {
            // 先提交本窗所有专家，再按同一个截止时间收集结果。
            for (Supplier<T> request : requests) {
                if (!clock.instant().isBefore(deadline)) {
                    break;
                }
                try {
                    active.add(submit(request::get));
                } catch (ModelGatewayException error) {
                    // 入队失败是模型故障，不能计作 PASS。
                }
            }
            for (Future<T> pending : active) {
                try {
                    results.add(await(pending, deadline));
                } catch (ModelGatewayException error) {
                    // 隔离单个候选的失败，继续收集及时返回的有效结果。
                }
            }
        } finally {
            active.forEach(future -> future.cancel(true));
        }
        return List.copyOf(results);
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
                // 实录累积后输入显著变长，慢模型在 18 秒内易超时，这里放宽到 28 秒。
                int seconds = task == RuntimeTask.R04 ? 28 : 10;
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
