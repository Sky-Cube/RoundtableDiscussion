package com.aipanel.it;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** P07：事件游标、回放、终态 204 与 Last-Event-ID 优先级。 */
class P07EventStreamIT extends AbstractPanelIntegrationTest {

    @Test
    @DisplayName("终态房间按最新游标订阅返回 204，且不占用连接配额")
    void returnsNoContentWhenCaughtUp() throws Exception {
        String roomId = completedRoom();

        MvcResult result = mockMvc.perform(get("/api/v1/discussions/" + roomId + "/events")
                .param("after", String.valueOf(lastEventSeq(roomId)))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("非法游标返回 400：前导零、负数、超上界与非数字")
    void rejectsInvalidCursor() throws Exception {
        String roomId = completedRoom();

        for (String cursor : new String[] {"00", "-1", "999999", "abc", "1.5", "", " 1 "}) {
            MvcResult result = mockMvc.perform(get("/api/v1/discussions/" + roomId + "/events")
                    .param("after", cursor)).andReturn();
            assertThat(result.getResponse().getStatus()).as("cursor=" + cursor).isEqualTo(400);
            assertThat(errorOf(result).get("code").intValue()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("Last-Event-ID 优先于 after，回放全部已提交事件后关闭流")
    void replaysFromLastEventId() throws Exception {
        String roomId = completedRoom();

        MvcResult result = mockMvc.perform(get("/api/v1/discussions/" + roomId + "/events")
                .param("after", "999999")
                .header("Last-Event-ID", "0")
                .accept(TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SseEmitter 完成时异步结果为 null，getAsyncResult 返回即表示流已关闭；随后按异步分发读取响应体。
        result.getAsyncResult(10_000);
        MvcResult completed = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .asyncDispatch(result)).andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("event:discussion.status");
        assertThat(body).contains("event:utterance.added");
        assertThat(body).contains("event:participant.status");
        assertThat(body).contains("id:1");
        assertThat(body).contains("\"discussionId\":\"" + roomId + "\"");
    }

    @Test
    @DisplayName("房间不存在返回 404，房间标识非法返回 400")
    void rejectsUnknownRoom() throws Exception {
        MvcResult missing = mockMvc.perform(get("/api/v1/discussions/00000000-0000-4000-8000-0000000000ff/events"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorOf(missing).get("code").intValue()).isEqualTo(404);

        MvcResult malformed = mockMvc.perform(get("/api/v1/discussions/not-a-uuid/events")).andReturn();
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(malformed).get("code").intValue()).isEqualTo(400);
    }

    @Test
    @DisplayName("事件序号按房间连续递增，且与快照 lastEventSeq 一致")
    void keepsSequentialEventIds() throws Exception {
        String roomId = completedRoom();
        int max = lastEventSeq(roomId);

        for (int seq = 1; seq <= max; seq++) {
            assertThat(count("SELECT COUNT(*) FROM discussion_event WHERE discussion_id = ? AND seq = ?", roomId, seq))
                    .as("seq=" + seq)
                    .isEqualTo(1);
        }
        JsonNode snapshot = getData("/api/v1/discussions/" + roomId);
        assertThat(snapshot.get("lastEventSeq").asInt()).isEqualTo(max);
    }

    private String completedRoom() throws Exception {
        String topic = "事件流用例-" + java.util.UUID.randomUUID();
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("EVALUATE", 2)));
        MvcResult created = mockMvc.perform(post("/api/v1/discussions").contentType(APPLICATION_JSON)
                .content(createBody(topic, 2))).andReturn();
        String roomId = dataOf(created).get("id").asText();
        mockMvc.perform(post("/api/v1/discussions/" + roomId + "/start")).andReturn();

        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode snapshot = getData("/api/v1/discussions/" + roomId);
            if ("COMPLETED".equals(snapshot.get("status").asText())) {
                return roomId;
            }
            assertThat(snapshot.get("status").asText()).as("房间不应失败").isNotEqualTo("FAILED");
            Thread.sleep(50);
        }
        throw new AssertionError("等待讨论完成超时");
    }

    private int lastEventSeq(String roomId) throws Exception {
        return getData("/api/v1/discussions/" + roomId).get("lastEventSeq").asInt();
    }
}
