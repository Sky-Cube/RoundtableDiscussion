package com.aipanel.it;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.service.debate.ScriptedRuntimeGateway;
import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.aipanel.support.TestFaultInjector.Mode;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@org.springframework.test.context.TestPropertySource(properties = "panel.gateway.evidence-enabled=true")
class RuntimeDiscussionIT extends AbstractPanelIntegrationTest {

    private static final String PASS = """
            {"intent":"PASS","targetUtteranceSeq":null,"focusCode":null,"proposedClaim":null}
            """;

    @MockitoSpyBean
    private ScriptedRuntimeGateway runtime;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.aipanel.service.debate.CrossrefEvidenceLookup lookup;

    @ParameterizedTest
    @CsvSource({
        "EVALUATE,COMPARE_CRITERIA:3|CHALLENGE_CONDITIONS:4|STATE_RECOMMENDATION:3",
        "PROPOSE,GENERATE_OPTIONS:3|TEST_FEASIBILITY:4|DEFINE_PILOT:3",
        "SOLVE,IDENTIFY_HYPOTHESES:3|DESIGN_CHECKS:4|PRIORITIZE_ACTION:3",
        "EXPLORE,MAP_PERSPECTIVES:3|TEST_BOUNDARIES:4|WEIGH_IMPACT:3"
    })
    void followsPhaseQuotasAndFreezesEveryBid(String goal, String expected) throws Exception {
        var turns = new ConcurrentLinkedQueue<String>();
        var bids = new ConcurrentLinkedQueue<JsonNode>();
        doAnswer(invocation -> {
            RuntimeTask task = invocation.getArgument(0);
            Map<String, Object> input = invocation.getArgument(1);
            if (task == RuntimeTask.R03) {
                turns.add(input.get("phase").toString());
            }
            if (task == RuntimeTask.R02) {
                bids.add(objectMapper.valueToTree(input));
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));

        String id = draft(goal, "阶段名额实测-" + goal);
        assertThat(start(id)).isEqualTo(202);
        JsonNode room = await(id, "COMPLETED");
        List<String> phases = new ArrayList<>();
        for (String part : expected.split("\\|")) {
            String[] fields = part.split(":");
            phases.addAll(java.util.Collections.nCopies(Integer.parseInt(fields[1]), fields[0]));
        }
        assertThat(turns).containsExactlyElementsOf(phases);
        assertThat(bids).hasSize((phases.size() + 1) * 2);
        assertThat(bids.stream().filter(bid -> bid.path("closingReview").asBoolean())).hasSize(2);
        for (String phase : phases.stream().distinct().toList()) {
            var snapshots = bids.stream().filter(bid -> phase.equals(bid.path("phase").asText()))
                    .collect(java.util.stream.Collectors.groupingBy(bid -> bid.path("utterances")));
            for (List<JsonNode> window : snapshots.values()) {
                assertThat(window).hasSize(2);
                assertThat(window.stream().map(bid -> bid.path("expert").path("displayName").asText()))
                        .doesNotHaveDuplicates();
                assertThat(window).allMatch(bid -> bid.path("expert").isObject() && !bid.has("participants"));
            }
        }
        assertThat(kindCount(room, "SUMMARY")).isEqualTo(1);
        assertThat(readEvents(id).stream().filter(line -> line.contains("FRESH"))).hasSize(phases.size());
    }

    @Test
    void letsAnotherExpertAnswerAfterTenthTurnAndStopsWhenRepliesRunOut() throws Exception {
        doAnswer(invocation -> {
            RuntimeTask task = invocation.getArgument(0);
            Map<String, Object> input = invocation.getArgument(1);
            if (task == RuntimeTask.R02 && Boolean.TRUE.equals(input.get("closingReview"))) {
                var lines = objectMapper.valueToTree(input.get("utterances")).valueStream()
                        .filter(line -> "EXPERT".equals(line.path("kind").asText())).toList();
                var last = lines.getLast();
                var expert = objectMapper.valueToTree(input.get("expert")).path("participantId").asText();
                if (lines.size() == 10 && !expert.equals(last.path("participantId").asText())) {
                    return """
                            {"intent":"REBUT","targetUtteranceSeq":%d,"focusCode":"ASSUMPTION",
                             "proposedClaim":"缺少同岗对照时不能把变化归因于工具。"}
                            """.formatted(last.path("seq").asInt());
                }
                return PASS;
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));
        String id = draft("EXPLORE", "新增反例需要回应");
        assertThat(start(id)).isEqualTo(202);
        var room = await(id, "COMPLETED");
        var lines = room.path("transcript").valueStream()
                .filter(line -> "EXPERT".equals(line.path("kind").asText())).toList();
        assertThat(lines).hasSize(11);
        assertThat(lines.getLast().path("replyToUtteranceSeq")).isEqualTo(lines.get(9).path("seq"));
        assertThat(room.path("insightsThroughUtteranceSeq")).isEqualTo(lines.getLast().path("seq"));
        assertThat(kindCount(room, "SUMMARY")).isEqualTo(1);
    }

    @Test
    void opensBothSixExpertWindowsBeforeAnyBidReturns() throws Exception {
        var entered = new CountDownLatch(12);
        var release = new CountDownLatch(1);
        var arrivals = new ConcurrentLinkedQueue<String>();
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == RuntimeTask.R02) {
                Map<String, Object> input = invocation.getArgument(1);
                var expert = objectMapper.valueToTree(input.get("expert"));
                arrivals.add(input.get("topic") + "/" + expert.path("participantId").asText());
                entered.countDown();
                release.await(8, TimeUnit.SECONDS);
                return PASS;
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));
        List<String> rooms = new ArrayList<>();
        for (String topic : List.of("六人甲场", "六人乙场")) {
            gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("EXPLORE", 6)));
            rooms.add(dataOf(createDraft(createBody(topic, 6))).path("id").asText());
        }
        try {
            for (String room : rooms) {
                assertThat(start(room)).isEqualTo(202);
            }
            assertThat(entered.await(4, TimeUnit.SECONDS)).as("两场共十二位专家应在首个回包前进入报名").isTrue();
            assertThat(arrivals).hasSize(12).doesNotHaveDuplicates();
        } finally {
            release.countDown();
            for (String room : rooms) {
                await(room, "COMPLETED");
            }
        }
    }

    @Test
    void runsTwoRoomsAndReservesOneStartPerRoom() throws Exception {
        CountDownLatch opening = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == RuntimeTask.R05) {
                opening.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("测试未及时释放开场");
                }
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));
        String first = draft("EVALUATE", "并行房间甲");
        String second = draft("SOLVE", "并行房间乙");
        String third = draft("PROPOSE", "待释放容量丙");
        try {
            var requestA = java.util.concurrent.CompletableFuture.supplyAsync(() -> uncheckedStart(first));
            var duplicate = java.util.concurrent.CompletableFuture.supplyAsync(() -> uncheckedStart(first));
            assertThat(start(second)).isEqualTo(202);
            assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(requestA.get(5, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(duplicate.get(5, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(start(third)).isEqualTo(429);
            assertThat(readEvents(third)).isEmpty();
            assertThat(getData("/api/v1/discussions/" + third).path("status").asText()).isEqualTo("DRAFT");
        } finally {
            release.countDown();
        }
        assertThat(kindCount(await(first, "COMPLETED"), "OPENING")).isEqualTo(1);
        assertThat(kindCount(await(second, "COMPLETED"), "OPENING")).isEqualTo(1);
        assertThat(readEvents(first)).allMatch(body -> body.contains(first) && !body.contains(second));
        assertThat(readEvents(second)).allMatch(body -> body.contains(second) && !body.contains(first));
        assertThat(start(third)).isEqualTo(202);
        await(third, "COMPLETED");
    }

    @ParameterizedTest
    @CsvSource({"PASS,COMPLETED", "PARTIAL,COMPLETED", "FAULT,FAILED"})
    void distinguishesPassPartialFaultAndEmptyWindow(String mode, String expected) throws Exception {
        var tasks = new ConcurrentLinkedQueue<RuntimeTask>();
        doAnswer(invocation -> {
            RuntimeTask task = invocation.getArgument(0);
            tasks.add(task);
            if (task == RuntimeTask.R02) {
                Map<String, Object> input = invocation.getArgument(1);
                JsonNode expert = objectMapper.valueToTree(input.get("expert"));
                if ("FAULT".equals(mode) || ("PARTIAL".equals(mode)
                        && "COST".equals(expert.path("perspective").asText()))) {
                    throw new ModelGatewayException(ModelGatewayException.Kind.REJECTED, "合成调用失败");
                }
                return PASS;
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));

        String id = draft("SOLVE", "报名处理-" + mode);
        assertThat(start(id)).isEqualTo(202);
        JsonNode room = await(id, expected);
        assertThat(kindCount(room, "EXPERT")).isZero();
        if ("FAILED".equals(expected)) {
            assertThat(tasks).doesNotContain(RuntimeTask.R03, RuntimeTask.R06, RuntimeTask.R07);
            assertThat(room.path("errorCode").intValue()).isEqualTo(502);
        } else {
            assertThat(tasks.stream().filter(task -> task == RuntimeTask.R06)).hasSize(1);
            assertThat(kindCount(room, "SUMMARY")).isEqualTo(1);
        }
    }

    @Test
    void publishesOrdinarySpeechWhileFrontierResearchIsStillRunning() throws Exception {
        var searching = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var nextWindow = new CountDownLatch(1);
        var expanded = new ConcurrentLinkedQueue<String>();
        doAnswer(call -> {
            searching.countDown();
            release.await(8, TimeUnit.SECONDS);
            return List.of();
        }).when(lookup).search(any(), any());
        doAnswer(call -> {
            RuntimeTask task = call.getArgument(0);
            Map<String, Object> input = call.getArgument(1);
            if (task == RuntimeTask.R03) {
                expanded.add(input.get("proposal").toString());
            }
            if (task != RuntimeTask.R02) {
                return call.callRealMethod();
            }
            var lines = objectMapper.valueToTree(input.get("utterances")).valueStream()
                    .filter(line -> "EXPERT".equals(line.path("kind").asText())).toList();
            if (lines.size() >= 2) {
                return PASS;
            }
            if (!lines.isEmpty()) {
                nextWindow.countDown();
            }
            if ("COST".equals(objectMapper.valueToTree(input.get("expert")).path("perspective").asText())) {
                return """
                        {"intent":"RAISE","targetUtteranceSeq":null,"focusCode":"EVIDENCE",
                         "proposedClaim":"前沿研究对岗位需求的影响尚需核实。","speechReady":true,
                         "searchReason":"FRONTIER","searchQuery":"graduate hiring","searchPremise":"前沿研究对岗位需求的影响"}
                        """;
            }
            searching.await(3, TimeUnit.SECONDS);
            return lines.isEmpty() ? """
                    {"intent":"RAISE","targetUtteranceSeq":null,"focusCode":"TRADE_OFF",
                     "proposedClaim":"先比较本地执行成本再定方案。","speechReady":true}
                    """ : PASS;
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));
        String id = draft("SOLVE", "普通讨论与后台查证并行");
        assertThat(start(id)).isEqualTo(202);
        try {
            assertThat(nextWindow.await(4, TimeUnit.SECONDS)).isTrue();
            var room = getData("/api/v1/discussions/" + id);
            assertThat(kindCount(room, "EXPERT")).isEqualTo(1);
            assertThat(room.path("transcript").get(1).path("content").asText()).isEqualTo("先比较本地执行成本再定方案。");
            assertThat(expanded).isEmpty();
        } finally {
            release.countDown();
        }
        assertThat(kindCount(await(id, "COMPLETED"), "EXPERT")).isEqualTo(2);
        assertThat(expanded).containsExactly("前沿研究对岗位需求的影响尚需核实。");
        org.mockito.Mockito.verify(lookup).search(any(), any());
    }

    @Test
    void resumesAfterAnEntireBidWindowTimesOut() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == RuntimeTask.R02 && attempts.incrementAndGet() <= 4) {
                throw ModelGatewayException.timeout();
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));
        String id = draft("SOLVE", "报名超时后恢复");
        assertThat(start(id)).isEqualTo(202);
        assertThat(kindCount(await(id, "COMPLETED"), "EXPERT")).isPositive();
    }

    @Test
    void startsSpeechWhileAnotherExpertIsStillWaitingForTheModel() throws Exception {
        var speechStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            RuntimeTask task = invocation.getArgument(0);
            Map<String, Object> input = invocation.getArgument(1);
            if (task == RuntimeTask.R02 && speechStarted.getCount() != 0
                    && "COST".equals(objectMapper.valueToTree(input.get("expert")).path("perspective").asText())) {
                release.await(6, TimeUnit.SECONDS);
            }
            if (task == RuntimeTask.R03) {
                speechStarted.countDown();
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));
        String id = draft("EXPLORE", "快专家先发言");
        assertThat(start(id)).isEqualTo(202);
        try {
            assertThat(speechStarted.await(3, TimeUnit.SECONDS)).as("仍有慢报名时应开始发言").isTrue();
        } finally {
            release.countDown();
            await(id, "COMPLETED");
        }
    }

    @Test
    void rollsBackSummaryAndTerminalEventTogether() throws Exception {
        String id = draft("EXPLORE", "总结事务回滚");
        faultInjector.mode(Mode.SUMMARY);
        assertThat(start(id)).isEqualTo(202);
        JsonNode room = await(id, "FAILED");
        assertThat(kindCount(room, "EXPERT")).isEqualTo(10);
        assertThat(kindCount(room, "SUMMARY")).isZero();
        assertThat(readEvents(id)).noneMatch(event -> event.contains("\"kind\":\"SUMMARY\"")
                || event.contains("\"status\":\"COMPLETED\""));
        assertThat(readEvents(id).getLast()).contains("FAILED");
    }

    @Test
    void emitsFreshStaleAndRecoveryBeforeCompletion() throws Exception {
        CountDownLatch summary = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            RuntimeTask task = invocation.getArgument(0);
            Map<String, Object> input = invocation.getArgument(1);
            if (task == RuntimeTask.R04) {
                JsonNode transcript = objectMapper.valueToTree(input.get("utterances"));
                long turns = transcript.valueStream().filter(line -> "EXPERT".equals(line.path("kind").asText())).count();
                if (turns == 3) {
                    return """
                            {"insights":[{"kind":"CONSENSUS","text":"无效引文示例","evidence":[
                            {"utteranceSeq":2,"quote":"伪造引文样本"},{"utteranceSeq":3,"quote":"伪造引文样本"}]}]}
                            """;
                }
            } else if (task == RuntimeTask.R07) {
                summary.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) {
                    throw new AssertionError("测试未释放总结");
                }
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));

        String id = draft("EXPLORE", "直播结论暂缓恢复");
        MvcResult stream = mockMvc.perform(get("/api/v1/discussions/" + id + "/events").accept(TEXT_EVENT_STREAM))
                .andReturn();
        try {
            assertThat(start(id)).isEqualTo(202);
            assertThat(summary.await(6, TimeUnit.SECONDS)).isTrue();
            List<JsonNode> updates = new ArrayList<>();
            for (String event : readEvents(id)) {
                JsonNode payload = objectMapper.readTree(event).path("payload");
                if (payload.has("insightStatus")) {
                    updates.add(payload);
                }
            }
            assertThat(updates.stream().map(item -> item.path("insightStatus").asText()))
                    .containsExactly("FRESH", "FRESH", "STALE", "FRESH", "FRESH", "FRESH", "FRESH", "FRESH", "FRESH", "FRESH");
            assertThat(updates.getFirst().path("insights")).isEmpty();
            assertThat(updates.get(1).path("insights")).hasSize(2);
            assertThat(updates.get(2).path("insights")).isEqualTo(updates.get(1).path("insights"));
            assertThat(updates.get(2).path("insightsThroughUtteranceSeq"))
                    .isEqualTo(updates.get(1).path("insightsThroughUtteranceSeq"));
            assertThat(getData("/api/v1/discussions/" + id).path("status").asText()).isEqualTo("SUMMARIZING");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!stream.getResponse().getContentAsString().contains("STALE") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(stream.getResponse().getContentAsString()).contains("event:insight.updated", "STALE");
        } finally {
            release.countDown();
        }
        stream.getAsyncResult(5000);
        String body = mockMvc.perform(asyncDispatch(stream)).andReturn().getResponse().getContentAsString();
        assertThat(body.indexOf("\"kind\":\"SUMMARY\"")).isPositive().isLessThan(body.indexOf("\"status\":\"COMPLETED\""));
    }

    @Test
    void rollsBackProjectionAndSuccessEvent() throws Exception {
        String id = draft("EXPLORE", "投影事务回滚");
        faultInjector.mode(Mode.INSIGHTS);
        assertThat(start(id)).isEqualTo(202);
        JsonNode room = await(id, "COMPLETED");
        assertThat(room.path("insightStatus").asText()).isEqualTo("STALE");
        assertThat(room.path("insightsThroughUtteranceSeq").asInt()).isZero();
        assertThat(count("SELECT COUNT(*) FROM insight WHERE discussion_id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM insight_evidence WHERE discussion_id = ?", id)).isZero();
        assertThat(readEvents(id)).noneMatch(event -> event.contains("\"insightStatus\":\"FRESH\""));
    }

    private List<String> readEvents(String room) {
        return jdbcTemplate.queryForList("SELECT payload_json FROM discussion_event WHERE discussion_id = ? ORDER BY seq",
                String.class, room);
    }

    private String draft(String goal, String topic) throws Exception {
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid(goal, 2)));
        MvcResult result = createDraft(createBody(topic, 2));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return dataOf(result).path("id").asText();
    }

    private int start(String room) throws Exception {
        return mockMvc.perform(post("/api/v1/discussions/" + room + "/start")).andReturn().getResponse().getStatus();
    }

    private int uncheckedStart(String room) {
        try {
            return start(room);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private JsonNode await(String room, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        JsonNode snapshot;
        do {
            snapshot = getData("/api/v1/discussions/" + room);
            String actual = snapshot.path("status").asText();
            if ("COMPLETED".equals(actual) || "FAILED".equals(actual)) {
                assertThat(actual).as("房间状态").isEqualTo(status);
                return snapshot;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("房间未在测试时限内结束：" + snapshot.path("status").asText());
    }

    private static long kindCount(JsonNode room, String kind) {
        return room.path("transcript").valueStream().filter(line -> kind.equals(line.path("kind").asText())).count();
    }
}
