package com.aipanel.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** P05-GOAL-MIXED：混合议题只保存一个目标；非法目标与客户端越权字段被拒绝。 */
class P05GoalMixedIT extends AbstractPanelIntegrationTest {

    private static final String MIXED_TOPIC = "转人工率升高，请查原因并给整改方案";

    @Test
    @DisplayName("混合议题只保存单个 SOLVE 目标")
    void storesSingleSolveGoal() throws Exception {
        gateway.script(MIXED_TOPIC, StubModelGateway.Step.json(RosterFixtures.valid("SOLVE", 4)));

        MvcResult created = createDraft(createBody(MIXED_TOPIC));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = dataOf(created);
        assertThat(data.get("discussionGoal").isTextual()).isTrue();
        assertThat(data.get("discussionGoal").asText()).isEqualTo("SOLVE");
        assertThat(jdbcTemplate.queryForObject("SELECT discussion_goal FROM discussion WHERE id = ?", String.class,
                data.get("id").asText())).isEqualTo("SOLVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"topic\":\"转人工率升高，请查原因并给整改方案\",\"discussionGoal\":\"SOLVE\"}",
            "{\"topic\":\"转人工率升高，请查原因并给整改方案\",\"discussionGoal\":[\"SOLVE\"]}",
            "{\"topic\":\"转人工率升高，请查原因并给整改方案\",\"participants\":[]}"
    })
    @DisplayName("客户端自选目标或提交成员被 400 拒绝")
    void rejectsClientOwnedFields(String body) throws Exception {
        int discussions = count("SELECT COUNT(*) FROM discussion");

        MvcResult result = createDraft(body);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(400);
        assertThat(errorOf(result).get("data").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
        assertThat(gateway.totalCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"array", "unknown", "missing"})
    @DisplayName("模型返回数组、未知枚举或缺失目标时补偿一次后 502")
    void rejectsInvalidModelGoal(String variant) throws Exception {
        String topic = "混合目标用例-" + variant;
        ObjectNode node = RosterFixtures.base("SOLVE", 4);
        if ("array".equals(variant)) {
            node.putArray("discussionGoal").add("SOLVE");
        } else if ("unknown".equals(variant)) {
            node.put("discussionGoal", "VAGUE");
        } else {
            node.remove("discussionGoal");
        }
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.json(node)),
                StubModelGateway.Step.json(RosterFixtures.json(node)));
        int discussions = count("SELECT COUNT(*) FROM discussion");

        MvcResult result = createDraft(createBody(topic));

        assertThat(gateway.calls(topic)).isEqualTo(2);
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(502);
        assertThat(count("SELECT COUNT(*) FROM discussion WHERE topic = ?", topic)).isZero();
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
    }
}