package com.aipanel.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 管理台接口：全量房间、记录计数、事件流与状态筛选。 */
class P08AdminIT extends AbstractPanelIntegrationTest {

    @Test
    @DisplayName("总览返回各状态房间数量与全库记录量")
    void overviewCountsEveryStatus() throws Exception {
        JsonNode overview = getData("/api/v1/admin/overview");

        JsonNode counts = overview.get("discussionCounts");
        for (String status : new String[] {"DRAFT", "RUNNING", "SUMMARIZING", "COMPLETED", "FAILED"}) {
            assertThat(counts.has(status)).isTrue();
        }
        assertThat(overview.get("presetCount").asInt()).isEqualTo(5);
        assertThat(overview.get("totalDiscussions").asInt())
                .isEqualTo(counts.get("DRAFT").asInt() + counts.get("RUNNING").asInt()
                        + counts.get("SUMMARIZING").asInt() + counts.get("COMPLETED").asInt()
                        + counts.get("FAILED").asInt());
        assertThat(overview.get("eventCount").asInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("管理台列出全部房间并带记录计数，支持状态筛选与非法筛选拒绝")
    void listsAllRoomsWithCounts() throws Exception {
        String topic = "管理台列表用例-" + java.util.UUID.randomUUID();
        String roomId = createRoom(topic);
        startAndWait(roomId);

        JsonNode all = getData("/api/v1/admin/discussions");
        JsonNode target = null;
        for (JsonNode room : all) {
            if (roomId.equals(room.get("id").asText())) {
                target = room;
            }
        }
        assertThat(target).isNotNull();
        assertThat(target.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(target.get("utteranceCount").asInt()).isGreaterThanOrEqualTo(8);
        assertThat(target.get("insightCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(target.get("eventCount").asInt()).isEqualTo(target.get("lastEventSeq").asInt());
        assertThat(target.get("participantCount").asInt()).isEqualTo(3);

        JsonNode completed = getData("/api/v1/admin/discussions?status=completed");
        for (JsonNode room : completed) {
            assertThat(room.get("status").asText()).isEqualTo("COMPLETED");
        }

        MvcResult invalid = mockMvc.perform(get("/api/v1/admin/discussions?status=UNKNOWN")).andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(invalid).get("code").intValue()).isEqualTo(400);
    }

    @Test
    @DisplayName("管理台详情返回完整快照与事件流，事件载荷可解析")
    void detailReturnsSnapshotAndEvents() throws Exception {
        String topic = "管理台详情用例-" + java.util.UUID.randomUUID();
        String roomId = createRoom(topic);
        startAndWait(roomId);

        JsonNode detail = getData("/api/v1/admin/discussions/" + roomId);

        JsonNode snapshot = detail.get("snapshot");
        assertThat(snapshot.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(snapshot.get("transcript").size()).isGreaterThanOrEqualTo(8);
        assertThat(snapshot.get("insights").size()).isGreaterThanOrEqualTo(2);

        JsonNode events = detail.get("events");
        assertThat(events.size()).isEqualTo(snapshot.get("lastEventSeq").asInt());
        assertThat(events.get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(events.get(0).get("type").asText()).isNotBlank();
        assertThat(events.get(0).get("payload").get("discussionId").asText()).isEqualTo(roomId);
        assertThat(events.get(0).get("createdAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("管理台查询不存在的房间返回 404")
    void detailRejectsUnknownRoom() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/discussions/00000000-0000-4000-8000-0000000000ff"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(404);
    }

    @Test
    @DisplayName("按议题子串搜索，大小写不敏感，空结果返回空数组")
    void searchesByKeyword() throws Exception {
        String marker = "命中标记" + java.util.UUID.randomUUID().toString().substring(0, 6);
        String roomId = createRoom(marker + "：客服是否自研");
        createRoom("另一个议题-" + java.util.UUID.randomUUID().toString().substring(0, 6));

        JsonNode matched = search("keyword", marker);
        assertThat(matched.size()).isEqualTo(1);
        assertThat(matched.get(0).get("id").asText()).isEqualTo(roomId);

        String latin = "LangCase ABC " + java.util.UUID.randomUUID().toString().substring(0, 6);
        createRoom(latin);
        JsonNode caseInsensitive = search("keyword", "langcase abc");
        assertThat(caseInsensitive.size()).isGreaterThanOrEqualTo(1);

        assertThat(search("keyword", "不存在-" + marker)).isEmpty();
    }

    @Test
    @DisplayName("按日期搜索：今天能命中刚创建的房间，其他日期为空")
    void searchesByDate() throws Exception {
        String marker = "日期检索" + java.util.UUID.randomUUID().toString().substring(0, 6);
        String roomId = createRoom(marker);

        String today = java.time.LocalDate.now().toString();
        JsonNode todayRooms = search("keyword", marker, "date", today);
        assertThat(todayRooms.size()).isEqualTo(1);
        assertThat(todayRooms.get(0).get("id").asText()).isEqualTo(roomId);

        assertThat(search("keyword", marker, "date", "2000-01-01")).isEmpty();
    }

    @Test
    @DisplayName("日期或关键词非法返回 400")
    void rejectsInvalidSearch() throws Exception {
        for (MvcResult result : new MvcResult[] {
                mockMvc.perform(get("/api/v1/admin/discussions").param("date", "2026-13-40")).andReturn(),
                mockMvc.perform(get("/api/v1/admin/discussions").param("date", "2026/11/20")).andReturn(),
                mockMvc.perform(get("/api/v1/admin/discussions").param("keyword", "长".repeat(61))).andReturn(),
        }) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorOf(result).get("code").intValue()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("状态与关键词可以组合筛选")
    void combinesStatusAndKeyword() throws Exception {
        String marker = "组合筛选" + java.util.UUID.randomUUID().toString().substring(0, 6);
        String roomId = createRoom(marker);
        startAndWait(roomId);

        JsonNode completed = search("status", "COMPLETED", "keyword", marker);
        assertThat(completed.size()).isEqualTo(1);
        assertThat(completed.get(0).get("id").asText()).isEqualTo(roomId);

        assertThat(search("status", "DRAFT", "keyword", marker)).isEmpty();
    }

    /** 用真正的请求参数发起检索，避免在 URL 模板里手工编码中文。 */
    private JsonNode search(String... keyValues) throws Exception {
        var builder = get("/api/v1/admin/discussions");
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            builder.param(keyValues[index], keyValues[index + 1]);
        }
        return dataOf(mockMvc.perform(builder).andReturn());
    }

    private String createRoom(String topic) throws Exception {
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("EVALUATE", 2)));
        MvcResult created = mockMvc.perform(post("/api/v1/discussions").contentType(APPLICATION_JSON)
                .content(createBody(topic, 2))).andReturn();
        return dataOf(created).get("id").asText();
    }

    private void startAndWait(String roomId) throws Exception {
        mockMvc.perform(post("/api/v1/discussions/" + roomId + "/start")).andReturn();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode snapshot = getData("/api/v1/discussions/" + roomId);
            if ("COMPLETED".equals(snapshot.get("status").asText())) {
                return;
            }
            assertThat(snapshot.get("status").asText()).isNotEqualTo("FAILED");
            Thread.sleep(50);
        }
        throw new AssertionError("等待讨论完成超时");
    }
}
