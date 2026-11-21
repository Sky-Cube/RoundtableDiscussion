package com.aipanel.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import com.aipanel.config.GatewayConditions;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aipanel.config.PanelProperties;
import com.aipanel.config.PanelProperties.Gateway;
import com.aipanel.gateway.ModelGatewayException.Kind;
import com.aipanel.service.RosterValidator;
import com.aipanel.service.debate.DiscussionWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Profile("!test")
@Conditional(GatewayConditions.Real.class)
public class OpenAiModelGateway implements ModelGateway, RuntimeGateway {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiModelGateway.class);
    private static final int MAX_BODY = 128 * 1024;
    private static final List<String> CODES = List.of("OUTPUT_TRUNCATED", "JSON_INVALID", "SHAPE_INVALID", "OUT_OF_RANGE", "INVALID_TARGET",
            "INVALID_QUOTE", "INVALID_EVIDENCE", "UNSUPPORTED_CLAIM", "PERSPECTIVE_DUPLICATE", "LOW_QUALITY",
            "INVALID_SENTENCES");
    private static final List<String> ROSTER_CODES = List.of("OUTPUT_TRUNCATED", "JSON_INVALID", "SHAPE_INVALID", "OUT_OF_RANGE",
            ValidationCodes.GOAL_MISSING, ValidationCodes.GOAL_INVALID, ValidationCodes.PARTICIPANT_COUNT,
            ValidationCodes.HOST_NOT_FIRST, ValidationCodes.HOST_COUNT, ValidationCodes.HOST_FIELD_INVALID,
            ValidationCodes.ROLE_INVALID, ValidationCodes.PERSPECTIVE_INVALID, ValidationCodes.PERSPECTIVE_DUPLICATE,
            ValidationCodes.STANCE_INVALID, ValidationCodes.NAME_DUPLICATE, ValidationCodes.FIELD_LENGTH_INVALID,
            ValidationCodes.LOW_QUALITY);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gateway config;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PromptTemplates templates;
    private final URI endpoint;
    private final RuntimeContract contract;
    private final ContentGuard guard;

    public OpenAiModelGateway(PanelProperties properties, RuntimeContract contract, ObjectMapper mapper, Clock clock,
            ContentGuard guard, DiscussionWorkflow workflow) {
        config = properties.gateway();
        this.mapper = mapper;
        this.clock = clock;
        this.contract = contract;
        this.guard = guard;
        PromptTemplates loaded = null;
        URI uri = null;
        try {
            loaded = new PromptTemplates(config, contract, workflow);
            if (configured(config.baseUrl()) && configured(config.model()) && configured(config.apiKey())) {
                uri = endpoint(config.baseUrl());
            }
        } catch (IOException | IllegalArgumentException error) {
            LOG.warn("模型端点或模板配置无效，templateVersion={}", config.templateVersion());
        }
        templates = loaded;
        endpoint = uri;
    }

    @Override
    public String generateRoster(String topic, int expertCount, List<String> codes) {
        Map<String, Object> input = Map.of("topic", topic, "expertCount", expertCount, "allowedPerspectives",
                RosterValidator.PERSPECTIVES);
        String output = generate(RuntimeTask.R01, input, codes,
                clock.instant().plusSeconds(config.timeouts().rosterSeconds()));
        guard.checkOutput(contract.parse(RuntimeTask.R01, output));
        return output;
    }

    @Override
    public int inputLimit(RuntimeTask task) {
        return config.contextWindow() - task.outputTokens() - 256;
    }

    @Override
    public int estimate(RuntimeTask task, Map<String, Object> input) {
        if (templates == null) {
            return 0;
        }
        String system = templates.assemble(task, input, true);
        String data = mapper.valueToTree(input).toString();
        // 按中文、标点与 ASCII 运行长度估算，并为修正字段及包装额外保留空间。
        return estimateText(system) + estimateText(data) + 96;
    }

    private static int estimateText(String text) {
        int units = 0;
        int asciiRun = 0;
        for (int codePoint : text.codePoints().toArray()) {
            if (codePoint < 128 && Character.isLetterOrDigit(codePoint)) {
                asciiRun++;
            } else {
                units += (asciiRun + 2) / 3 + (codePoint < 128 ? 1 : 2);
                asciiRun = 0;
            }
        }
        return units + (asciiRun + 2) / 3;
    }

    @Override
    public String generate(RuntimeTask task, Map<String, Object> input, List<String> rawCodes, Instant deadline) {
        if (endpoint == null || templates == null) {
            throw ModelGatewayException.configMissing();
        }
        Instant started = clock.instant();
        Duration timeout = Duration.between(started, deadline);
        if (timeout.isZero() || timeout.isNegative()) {
            throw ModelGatewayException.timeout();
        }
        int estimate = estimate(task, input);
        int outputLimit = task.outputTokens();
        if (rawCodes.contains("OUTPUT_TRUNCATED")) {
            outputLimit = Math.min(outputLimit * 2, config.contextWindow() - estimate - 256);
        }
        if (estimate > inputLimit(task)) {
            throw new ModelGatewayException(Kind.CONTEXT_UNAVAILABLE, "模型上下文超限");
        }
        List<String> allowedCodes = task == RuntimeTask.R01 ? ROSTER_CODES : CODES;
        List<String> normalized = rawCodes.stream()
                .map(code -> allowedCodes.contains(code) ? code : "SHAPE_INVALID").toList();
        List<String> codes = allowedCodes.stream().filter(normalized::contains).toList();
        var data = new HashMap<>(input);
        if (!codes.isEmpty()) {
            data.put("validationCodes", codes);
        }
        String system = templates.assemble(task, input, !codes.isEmpty());
        int status = 0;
        try {
            String userContent = mapper.writeValueAsString(sanitize(mapper.valueToTree(data)));
            Map<String, Object> requestBody = new HashMap<>(Map.of(
                    "model", config.model(), "max_tokens", outputLimit,
                    "messages", List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", userContent)),
                    "response_format", Map.of("type", "json_object")));
            if (!"off".equalsIgnoreCase(config.reasoningEffort()) && !config.model().contains("deepseek")) {
                // 圆桌保留快速推理，但推理 token 也占输出上限，需配合提高后的各任务上限。
                requestBody.put("reasoning_effort", config.reasoningEffort());
            }
            if ("deepseek-flash".equals(config.model())) {
                // 模型默认将推理计入输出上限，结构化任务直接生成 JSON。
                requestBody.put("thinking", Map.of("type", "disabled"));
            }
            String json = mapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var response = BoundedHttp.send(client, request, timeout, MAX_BODY);
            status = response.statusCode();
            if (status != 200) {
                throw classify(status, response.body());
            }
            JsonNode reply = mapper.readTree(response.body());
            JsonNode choice = reply.path("choices").path(0);
            String finish = choice.path("finish_reason").asText();
            JsonNode message = choice.path("message");
            if ("content_filter".equals(finish) || (message.hasNonNull("refusal")
                    && !message.path("refusal").asText().isBlank())) {
                throw ModelGatewayException.blocked();
            }
            if (!"stop".equals(finish) || !message.path("content").isTextual()) {
                String finishCategory = switch (finish) {
                    case "stop", "length", "tool_calls" -> finish;
                    default -> "unknown";
                };
                LOG.info("模型正文未完成 task={} finish={} contentPresent={} completionTokens={}",
                        task, finishCategory, message.path("content").isTextual()
                                && !message.path("content").asText().isBlank(),
                        reply.path("usage").path("completion_tokens").asInt(-1));
                throw new ModelGatewayException(Kind.FORMAT_INVALID,
                        "length".equals(finish) ? "OUTPUT_TRUNCATED" : "JSON_INVALID");
            }
            String content = message.path("content").asText();
            return content;
        } catch (TimeoutException error) {
            throw ModelGatewayException.timeout();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(Kind.STALE_RESPONSE, "模型请求已取消");
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new ModelGatewayException(Kind.FORMAT_INVALID, "JSON_INVALID");
        } catch (IOException error) {
            Kind kind = error.getCause() instanceof java.net.http.HttpTimeoutException ? Kind.TIMEOUT : Kind.TRANSIENT;
            throw new ModelGatewayException(kind, "模型连接失败");
        } finally {
            LOG.info("模型请求 task={} templateVersion={} status={} durationMs={} estimatedInputTokens={} repair={}",
                    task, templates.version(), status, Duration.between(started, clock.instant()).toMillis(),
                    estimate, !codes.isEmpty());
        }
    }

    private JsonNode sanitize(JsonNode input) {
        if (input.isTextual()) {
            return mapper.getNodeFactory().textNode(guard.safeContext(input.asText()));
        }
        if (input.isObject()) {
            var object = mapper.createObjectNode();
            input.fields().forEachRemaining(field -> object.set(field.getKey(), sanitize(field.getValue())));
            return object;
        }
        if (input.isArray()) {
            var array = mapper.createArrayNode();
            input.forEach(item -> array.add(sanitize(item)));
            return array;
        }
        return input;
    }

    private static ModelGatewayException classify(int status, byte[] body) {
        String response = new String(body, StandardCharsets.UTF_8);
        if (response.contains("content_filter") || response.contains("content_policy_violation")) {
            return ModelGatewayException.blocked();
        }
        if (response.contains("context_length_exceeded")) {
            return new ModelGatewayException(Kind.CONTEXT_UNAVAILABLE, "提供方拒绝上下文长度");
        }
        if (status == 408 || status == 504) {
            return ModelGatewayException.timeout();
        }
        Kind kind = status == 429 || status == 500 || status == 502 || status == 503
                ? Kind.TRANSIENT : Kind.REJECTED;
        return new ModelGatewayException(kind, "模型 HTTP 状态 " + status);
    }

    private static URI endpoint(String value) {
        URI base = URI.create(value.strip());
        boolean loopback = List.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equalsIgnoreCase(base.getScheme()) || (loopback && "http".equals(base.getScheme())))) {
            throw new IllegalArgumentException("模型端点无效");
        }
        String path = base.toString().replaceFirst("/+$", "");
        return URI.create(path.endsWith("/chat/completions") ? path : path + "/chat/completions");
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
