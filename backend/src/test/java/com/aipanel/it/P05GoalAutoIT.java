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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** P05-GOAL-AUTO：四类提问分别落库对应讨论目标，快照与写入值一致。 */
class P05GoalAutoIT extends AbstractPanelIntegrationTest {

    @Test
    @DisplayName("评估型议题保存 EVALUATE")
    void storesEvaluate() throws Exception {
        assertGoal("AI 客服该自研还是采购", "EVALUATE");
    }

    @Test
    @DisplayName("提案型议题保存 PROPOSE")
    void storesPropose() throws Exception {
        assertGoal("为 20 人客服团队设计 AI 辅助接待方案", "PROPOSE");
    }

    @Test
    @DisplayName("排查型议题保存 SOLVE")
    void storesSolve() throws Exception {
        assertGoal("AI 客服上线后转人工率升高，查原因并整改", "SOLVE");
    }

    @Test
    @DisplayName("探索型议题保存 EXPLORE")
    void storesExplore() throws Exception {
        assertGoal("AI 客服会改变哪些服务场景", "EXPLORE");
    }

    private void assertGoal(String topic, String goal) throws Exception {
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid(goal, 4)));

        MvcResult created = createDraft(createBody(topic));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = dataOf(created);
        assertThat(data.get("discussionGoal").asText()).isEqualTo(goal);
        assertThat(data.get("status").asText()).isEqualTo("DRAFT");

        String roomId = data.get("id").asText();
        String stored = jdbcTemplate.queryForObject(
                "SELECT discussion_goal FROM discussion WHERE id = ?", String.class, roomId);
        assertThat(stored).isEqualTo(goal);

        String snapshot = objectMapper
                .readTree(mockMvc.perform(get("/api/v1/discussions/" + roomId).accept(APPLICATION_JSON))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data").get("discussionGoal").asText();
        assertThat(snapshot).isEqualTo(goal);
        assertThat(gateway.calls(topic)).isEqualTo(1);
    }
}