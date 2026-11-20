package com.aipanel.it;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.aipanel.config.PanelProperties;
import com.aipanel.config.PanelProperties.Gateway;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.ModelGatewayException.Kind;
import com.aipanel.gateway.OpenAiModelGateway;
import com.aipanel.gateway.PromptTemplates;
import com.aipanel.gateway.RuntimeContract;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.service.debate.DiscussionBudget;
import com.aipanel.service.debate.DiscussionWorkflow;
import com.aipanel.service.debate.RuntimeCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class P08ModelGatewayIT {

    private final Clock clock = Clock.systemUTC();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContentGuard guard = new ContentGuard();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<JsonNode> requests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> authorization = new ConcurrentLinkedQueue<>();

    private HttpServer server;
    private ThreadPoolTaskExecutor pool;
    private RuntimeContract contract;
    private Gateway settings;

    @BeforeEach
    void open() throws IOException {
        contract = new RuntimeContract(mapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                requests.add(mapper.readTree(exchange.getRequestBody()));
                Reply reply = replies.remove();
                if (reply.delayMs() > 0) {
                    try {
                        Thread.sleep(reply.delayMs());
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] bytes = reply.body().getBytes(UTF_8);
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        settings = new Gateway("real", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fake-model",
                "fake-key", null, "v2.1", 8192, Map.of(), false);
        pool = new ThreadPoolTaskExecutor();
        pool.initialize();
    }

    @AfterEach
    void close() {
        if (server != null) {
            server.stop(0);
        }
        if (pool != null) {
            pool.destroy();
        }
    }

    @Test
    void sendsSeparateMessagesRedactsInputAndIgnoresProviderReasoning() throws Exception {
        replies.add(ok("{\"sentences\":[\"请各位说明比较标准及证据来源。\"]}"));
        JsonNode output = run(client(settings), input("EVALUATE", "评估采纳条件 contact@example.test"));
        assertThat(output.path("sentences").get(0).asText()).contains("比较标准");
        JsonNode sent = requests.remove();
        assertThat(sent.path("model").asText()).isEqualTo("fake-model");
        assertThat(sent.has("thinking")).isFalse();
        assertThat(authorization.remove()).isEqualTo("Bearer fake-key");
        assertThat(sent.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(sent.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(sent.at("/messages/0/content").asText()).doesNotContain("contact@example.test", "评估采纳条件");
        assertThat(mapper.readTree(sent.at("/messages/1/content").asText()).path("topic").asText())
                .isEqualTo("评估采纳条件 [邮箱]");
        assertThat(output.toString()).doesNotContain("provider-only");
    }

    @Test
    void disablesDeepSeekFlashThinkingForShortStructuredOutput() {
        var config = new Gateway("real", settings.baseUrl(), "deepseek-flash", settings.apiKey(),
                null, "v2.1", 8192, Map.of(), false);
        replies.add(ok("{\"sentences\":[\"请各位说明比较标准。\"]}"));
        run(client(config), input("EVALUATE", "公开测试议题"));
        JsonNode sent = requests.remove();
        assertThat(sent.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(RuntimeTask.R05.outputTokens());
    }

    @Test
    void loadsAndValidatesRosterSchemaWithinDefaultBudget() {
        replies.add(ok(com.aipanel.support.RosterFixtures.valid("EVALUATE", 2)));
        var gateway = client(settings);
        String result = gateway.generateRoster("客服应先自研还是采购", 2, List.of());
        assertThat(contract.parse(RuntimeTask.R01, result).path("discussionGoal").asText()).isEqualTo("EVALUATE");
        assertThat(requests).hasSize(1);
    }

    @Test
    void sendsRosterRepairCodesWithOriginalInputs() throws Exception {
        replies.add(ok(com.aipanel.support.RosterFixtures.valid("EVALUATE", 2)));
        client(settings).generateRoster("客服应先自研还是采购", 2,
                List.of("NAME_DUPLICATE", "PARTICIPANT_COUNT", "NAME_DUPLICATE"));
        JsonNode sent = requests.remove();
        JsonNode data = mapper.readTree(sent.at("/messages/1/content").asText());
        assertThat(data.path("expertCount").asInt()).isEqualTo(2);
        assertThat(data.path("allowedPerspectives")).hasSize(6);
        assertThat(data.path("validationCodes").valueStream().map(JsonNode::asText))
                .containsExactly("PARTICIPANT_COUNT", "NAME_DUPLICATE");
    }

    @Test
    void repairsSchemaFailureWithOriginalDataAndOneExtraRequest() throws Exception {
        replies.add(ok("{\"sentences\":[\"请各位回应题设问题。\"],\"status\":\"COMPLETED\"}"));
        replies.add(ok("{\"sentences\":[\"请各位说明可复核的前提。\"]}"));
        run(client(settings), input("EVALUATE", "公开测试议题"));
        JsonNode first = mapper.readTree(requests.remove().at("/messages/1/content").asText());
        JsonNode repair = mapper.readTree(requests.remove().at("/messages/1/content").asText());
        assertThat(first.has("validationCodes")).isFalse();
        assertThat(repair.path("validationCodes").get(0).asText()).isEqualTo("SHAPE_INVALID");
        assertThat(repair.path("topic")).isEqualTo(first.path("topic"));
        assertThat(repair.toString()).doesNotContain("COMPLETED");
    }

    @Test
    void sendsSpecificRepairCodeForInvalidSentencePunctuation() throws Exception {
        replies.add(ok("{\"sentences\":[\"本场讨论开放时间。哪些需求应优先？\"]}"));
        replies.add(ok("{\"sentences\":[\"本场讨论开放时间。\",\"哪些需求应优先？\"]}"));
        run(client(settings), input("PROPOSE", "社区图书馆开放时间"));
        requests.remove();
        JsonNode repair = mapper.readTree(requests.remove().at("/messages/1/content").asText());
        assertThat(repair.path("validationCodes").get(0).asText()).isEqualTo("INVALID_SENTENCES");
    }

    @Test
    void rateLimitAndStructureFailureShareOneExtraRequest() {
        replies.add(new Reply(429, "{}", 0));
        replies.add(ok("{\"sentences\":[]}"));
        assertFailure(Kind.FORMAT_INVALID, () -> run(client(settings), input("SOLVE", "合成排查议题")));
        assertThat(requests).hasSize(2);
        assertThat(requests.peek()).isEqualTo(List.copyOf(requests).getLast());
    }

    @Test
    void timesOutAndDoesNotTrustProviderRefusalOrSecrets() {
        replies.add(new Reply(200, "{}", 500));
        var gateway = client(settings);
        assertFailure(Kind.TIMEOUT, () -> gateway.generate(RuntimeTask.R05, input("PROPOSE", "限时合成议题"),
                List.of(), clock.instant().plusMillis(150)));
        assertThat(requests).hasSize(1);
    }

    @Test
    void blocksSensitiveOutputWithoutCompensation() throws Exception {
        replies.add(ok("{\"sentences\":[\"联系 contact@example.test 获取资料。\"]}"));
        assertFailure(Kind.CONTENT_BLOCKED, () -> run(client(settings), input("EXPLORE", "合成安全议题")));
        assertThat(requests).hasSize(1);
        var refusal = mapper.createObjectNode();
        refusal.putArray("choices").addObject().put("finish_reason", "content_filter")
                .putObject("message").put("content", "");
        replies.add(new Reply(200, mapper.writeValueAsString(refusal), 0));
        assertFailure(Kind.CONTENT_BLOCKED, () -> run(client(settings), input("EXPLORE", "合成拦截议题")));
        assertThat(requests).hasSize(2);
    }

    @Test
    void usesFourGoalStrategiesAndCustomTemplates(@TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Files.writeString(temp.resolve("custom.md"), "定制开场模板标记。");
        // 类路径模板继续提供其余策略，显式映射只覆盖单个角色。
        var templateConfig = new Gateway("real", settings.baseUrl(), settings.model(), settings.apiKey(),
                null, "v2.2", 8192, Map.of("roles/host-opening.md", "roles/host-intervention.md"), false);
        var workflow = DiscussionWorkflow.load(null, mapper);
        var templates = new PromptTemplates(templateConfig, contract, workflow);
        var prompts = List.of("EVALUATE", "PROPOSE", "SOLVE", "EXPLORE").stream()
                .map(goal -> templates.assemble(RuntimeTask.R05, input(goal, "测试主题"), false)).toList();
        assertThat(prompts).doesNotHaveDuplicates().allMatch(prompt -> prompt.contains("输出 JSON Schema"));
        assertThat(templates.version()).isEqualTo("v2.2");
        assertThat(prompts.getFirst()).isNotEqualTo(new PromptTemplates(settings, contract, workflow)
                .assemble(RuntimeTask.R05, input("EVALUATE", "测试主题"), false));

        var missing = new Gateway("real", settings.baseUrl(), settings.model(), settings.apiKey(),
                temp.toString(), "v2.2", 8192, Map.of(), false);
        assertFailure(Kind.CONFIG_MISSING, () -> run(client(missing), input("EVALUATE", "测试主题")));
        var unconfigured = new Gateway("real", null, null, null, null, "v2.1", 8192, Map.of(), false);
        assertFailure(Kind.CONFIG_MISSING, () -> run(client(unconfigured), input("EVALUATE", "测试主题")));
        assertThat(requests).isEmpty();
    }

    @Test
    void placesWrittenRoleAndStageTemplatesInUpstreamRequests() throws Exception {
        replies.add(ok(com.aipanel.support.RosterFixtures.valid("PROPOSE", 2)));
        var gateway = client(settings);
        String topic = "这是只出现在数据消息里的合成议题";
        gateway.generateRoster(topic, 2, List.of());
        JsonNode rosterMessages = requests.remove().path("messages");
        assertThat(rosterMessages.at("/0/content").asText()).containsSubsequence(
                "[主持角色建档]", "[专家角色建档]", "先识别题目", "EVALUATE\n", "PROPOSE\n",
                "SOLVE\n", "EXPLORE\n", "输出 JSON Schema").doesNotContain(topic);
        assertThat(mapper.readTree(rosterMessages.at("/1/content").asText()).path("topic").asText()).isEqualTo(topic);

        replies.add(ok("{\"intent\":\"PASS\",\"targetUtteranceSeq\":null,\"focusCode\":null,\"proposedClaim\":null}"));
        Map<String, Object> role = Map.of("displayName", "合成专家", "perspective", "DELIVERY");
        gateway.generate(RuntimeTask.R02,
                Map.of("discussionGoal", "PROPOSE", "phase", "TEST_FEASIBILITY", "expert", role),
                List.of(), clock.instant().plusSeconds(5));
        JsonNode bidMessages = requests.remove().path("messages");
        assertThat(bidMessages.at("/0/content").asText())
                .contains("只代表输入中的 expert", "本阶段关注可行性检验")
                .doesNotContain("本阶段关注评价标准的比较", "合成专家");
        assertThat(mapper.readTree(bidMessages.at("/1/content").asText()).at("/expert/displayName").asText())
                .isEqualTo("合成专家");
    }

    @Test
    void rejectsDuplicateKeysAndTrailingJson() {
        assertFailure(Kind.FORMAT_INVALID, () -> contract.parse(RuntimeTask.R03,
                "{\"sentences\":[\"保留这个条件。\"],\"sentences\":[\"覆盖这个条件。\"]}"));
        assertFailure(Kind.FORMAT_INVALID, () -> contract.parse(RuntimeTask.R03,
                "{\"sentences\":[\"保留这个条件。\"]} {}"));
    }

    private OpenAiModelGateway client(Gateway config) {
        try {
            var workflow = DiscussionWorkflow.load(config.templateDirectory(), mapper);
            return new OpenAiModelGateway(new PanelProperties(null, config, null, null), contract, mapper, clock,
                    guard, workflow);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private JsonNode run(OpenAiModelGateway gateway, Map<String, Object> request) {
        DiscussionBudget budget = new DiscussionBudget(clock);
        return new RuntimeCalls(gateway, contract, guard, pool, clock).invoke(RuntimeTask.R05, request, budget,
                false, Instant.now().plusSeconds(3), json -> json);
    }

    private Reply ok(String output) {
        var message = mapper.createObjectNode().put("content", output).put("reasoning_content", "provider-only");
        var root = mapper.createObjectNode();
        root.putArray("choices").addObject().put("finish_reason", "stop").set("message", message);
        return new Reply(200, root.toString(), 0);
    }

    private static Map<String, Object> input(String goal, String topic) {
        return Map.of("discussionGoal", goal, "phase", "OPENING", "topic", topic);
    }

    private static void assertFailure(Kind kind, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatExceptionOfType(ModelGatewayException.class).isThrownBy(call).satisfies(error ->
                assertThat(error.kind()).isEqualTo(kind));
    }

    private record Reply(int status, String body, long delayMs) {
    }
}
