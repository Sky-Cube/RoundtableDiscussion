package com.aipanel.it;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** P06：确认开场、讨论编排、实时结论与终端状态。 */
class P06DiscussionFlowIT extends AbstractPanelIntegrationTest {

    @Test
    @DisplayName("确认开场返回 202，重复确认不重复派发，跑完后有实录、结论与收尾")
    void runsDiscussionToCompletion() throws Exception {
        String roomId = createRoom("讨论编排用例", 4);

        MvcResult started = start(roomId);
        assertThat(started.getResponse().getStatus()).isEqualTo(202);
        JsonNode startBody = dataOf(started);
        assertThat(startBody.get("id").asText()).isEqualTo(roomId);
        assertThat(startBody.get("status").asText()).isEqualTo("RUNNING");

        MvcResult repeated = start(roomId);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(202);
        assertThat(dataOf(repeated).get("status").asText()).isIn("RUNNING", "SUMMARIZING", "COMPLETED");

        JsonNode snapshot = awaitStatus(roomId, "COMPLETED");
        JsonNode transcript = snapshot.get("transcript");
        assertThat(transcript.size()).isGreaterThanOrEqualTo(8);

        List<String> kinds = new ArrayList<>();
        transcript.forEach(line -> kinds.add(line.get("kind").asText()));
        assertThat(kinds.get(0)).isEqualTo("OPENING");
        assertThat(kinds).contains("BRIDGE").contains("SUMMARY");
        assertThat(kinds.stream().filter("EXPERT"::equals).count()).isEqualTo(10);
        assertThat(kinds.get(kinds.size() - 1)).isEqualTo("SUMMARY");

        assertThat(transcript.get(1).get("replyToUtteranceSeq").isNull()).isTrue();
        assertThat(snapshot.get("summary").isNull()).isFalse();
        assertThat(snapshot.get("summary").asText()).isNotBlank();

        assertThat(snapshot.get("insights").size()).isGreaterThanOrEqualTo(2);
        assertThat(snapshot.get("insightStatus").asText()).isEqualTo("FRESH");
        assertThat(snapshot.get("insightsThroughUtteranceSeq").asInt()).isGreaterThan(0);
        for (JsonNode insight : snapshot.get("insights")) {
            JsonNode evidence = insight.get("evidenceUtteranceSeqs");
            assertThat(evidence.size()).isGreaterThanOrEqualTo(2);
            List<String> speakers = new ArrayList<>();
            for (JsonNode seq : evidence) {
                speakers.add(transcript.get(seq.asInt() - 1).get("participantId").asText());
            }
            assertThat(speakers.stream().distinct().count()).isGreaterThanOrEqualTo(2);
        }
        for (JsonNode member : snapshot.get("participants")) {
            assertThat(member.get("stageStatus").asText()).isEqualTo("IDLE");
            assertThat(member.get("publicFocus").isNull()).isTrue();
        }
        assertThat(snapshot.get("lastEventSeq").asInt()).isGreaterThan(transcript.size());

        int eventRows = count("SELECT COUNT(*) FROM discussion_event WHERE discussion_id = ?", roomId);
        assertThat(eventRows).isEqualTo(snapshot.get("lastEventSeq").asInt());
        assertThat(count("SELECT COUNT(*) FROM utterance WHERE discussion_id = ?", roomId))
                .isEqualTo(transcript.size());
        assertThat(count("SELECT COUNT(*) FROM discussion_event WHERE discussion_id = ? AND type = 'utterance.added'",
                roomId)).isEqualTo(transcript.size());
    }

    @Test
    @DisplayName("终态房间再次确认返回 409")
    void rejectsRestartOfTerminalRoom() throws Exception {
        String roomId = createRoom("终态确认用例", 2);
        start(roomId);
        awaitStatus(roomId, "COMPLETED");

        MvcResult result = start(roomId);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(409);
    }

    @Test
    @DisplayName("容量已满时第三场返回 429 且保留草稿")
    void rejectsWhenCapacityIsFull() throws Exception {
        String first = insertRunningRoom("容量占用 A");
        String second = insertRunningRoom("容量占用 B");
        String draft = createRoom("容量占用 C", 2);
        try {
            MvcResult result = start(draft);

            assertThat(result.getResponse().getStatus()).isEqualTo(429);
            assertThat(errorOf(result).get("code").intValue()).isEqualTo(429);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM discussion WHERE id = ?", String.class, draft))
                    .isEqualTo("DRAFT");
        } finally {
            finishManually(first);
            finishManually(second);
        }
    }

    private MvcResult start(String roomId) throws Exception {
        return mockMvc.perform(post("/api/v1/discussions/" + roomId + "/start")).andReturn();
    }

    private String createRoom(String topic, int expertCount) throws Exception {
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("EVALUATE", expertCount)));
        MvcResult created = mockMvc.perform(post("/api/v1/discussions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(createBody(topic, expertCount))).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return dataOf(created).get("id").asText();
    }

    private JsonNode awaitStatus(String roomId, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/v1/discussions/" + roomId)).andReturn();
            last = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
            if (expected.equals(last.get("status").asText())) {
                return last;
            }
            if ("FAILED".equals(last.get("status").asText())) {
                throw new AssertionError("讨论执行失败：" + last);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待 " + expected + " 超时，当前：" + last);
    }

    /** 直接写入占用容量的 RUNNING 房间，避免依赖编排耗时。 */
    private String insertRunningRoom(String topic) {
        String id = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO discussion (id, topic, discussion_goal, expert_count, status, "
                        + "last_event_seq, insight_status, insights_through_utterance_seq, created_at, started_at, "
                        + "ended_at, error_code) VALUES (?, ?, 'EVALUATE', 2, 'RUNNING', 0, 'PENDING', NULL, ?, ?, NULL, NULL)",
                id, topic, java.time.Instant.now().toString(), java.time.Instant.now().toString());
        return id;
    }

    private void finishManually(String id) {
        jdbcTemplate.update("UPDATE discussion SET status = 'COMPLETED', ended_at = ? WHERE id = ?",
                java.time.Instant.now().toString(), id);
    }
}
