package com.aipanel.support;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aipanel.gateway.ModelGateway;
import com.aipanel.gateway.ModelGatewayException;

/**
 * 测试专用模型网关替身。按议题编排可控制的响应序列，并统计每个议题的调用次数。
 *
 * <p>只在 test profile 启用；生产与 dev 均不加载本类。
 */
@Component
@Profile("test")
public class StubModelGateway implements ModelGateway {

    private final Map<String, Deque<Step>> scripts = new ConcurrentHashMap<>();
    private final Map<String, Integer> calls = new ConcurrentHashMap<>();
    private final List<String> callLog = new CopyOnWriteArrayList<>();

    /** 为议题编排响应序列：第一次创建取第一条，R08 补偿取第二条。 */
    public void script(String topic, Step... steps) {
        Deque<Step> queue = new ConcurrentLinkedDeque<>();
        for (Step step : steps) {
            queue.add(step);
        }
        scripts.put(topic, queue);
        calls.put(topic, 0);
    }

    public int calls(String topic) {
        return calls.getOrDefault(topic, 0);
    }

    public int totalCalls() {
        return calls.values().stream().mapToInt(Integer::intValue).sum();
    }

    public List<String> callLog() {
        return new ArrayList<>(callLog);
    }

    public void reset() {
        scripts.clear();
        calls.clear();
        callLog.clear();
    }

    @Override
    public String generateRoster(String topic, int expertCount, List<String> validationCodes) {
        calls.merge(topic, 1, Integer::sum);
        callLog.add(topic + "|" + String.join(",", validationCodes));
        Deque<Step> queue = scripts.get(topic);
        Step step = queue == null ? null : queue.poll();
        if (step == null) {
            throw new IllegalStateException("未编排的网关调用：" + topic);
        }
        return step.run();
    }

    /** 一次可编排的网关行为。 */
    public static final class Step {

        private final String json;
        private final ModelGatewayException failure;

        private Step(String json, ModelGatewayException failure) {
            this.json = json;
            this.failure = failure;
        }

        public static Step json(String value) {
            return new Step(value, null);
        }

        public static Step timeout() {
            return new Step(null, ModelGatewayException.timeout());
        }

        public static Step blocked() {
            return new Step(null, ModelGatewayException.blocked());
        }

        public static Step upstreamError() {
            return new Step(null, new ModelGatewayException(ModelGatewayException.Kind.UPSTREAM_ERROR, "上游结构失败"));
        }

        public static Step configMissing() {
            return new Step(null, ModelGatewayException.configMissing());
        }

        String run() {
            if (failure != null) {
                throw failure;
            }
            return json;
        }
    }
}