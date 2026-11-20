package com.aipanel.it;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

/** P05-ROSTER：人数边界、后端分配标识、模型输出各类非法形态与失败回滚。 */
class P05RosterIT extends AbstractPanelIntegrationTest {

    @Test
    @DisplayName("省略人数时生成 1 位主持与 4 位专家，标识与色标在房间内唯一")
    void defaultsToFourExperts() throws Exception {
        String topic = "默认人数用例";
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("EVALUATE", 4)));

        MvcResult created = createDraft(createBody(topic));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("Location")).startsWith("/api/v1/discussions/");
        JsonNode data = dataOf(created);
        assertThat(data.get("expertCount").asInt()).isEqualTo(4);
        JsonNode participants = data.get("participants");
        assertThat(participants).hasSize(5);
        assertThat(participants.get(0).get("role").asText()).isEqualTo("HOST");
        assertThat(participants.get(0).get("id").asText()).isEqualTo("host");
        assertThat(participants.get(0).get("colorKey").asText()).isEqualTo("violet");
        Set<String> ids = new HashSet<>();
        Set<String> colors = new HashSet<>();
        for (JsonNode member : participants) {
            ids.add(member.get("id").asText());
            colors.add(member.get("colorKey").asText());
        }
        assertThat(ids).hasSize(5).contains("e1", "e2", "e3", "e4");
        assertThat(colors).hasSize(5);
        assertThat(data.get("transcript")).isEmpty();
        assertThat(data.get("insights")).isEmpty();
        assertThat(data.get("lastEventSeq").asInt()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 6})
    @DisplayName("人数边界 2 与 6 合法")
    void acceptsBoundaryExpertCounts(int expertCount) throws Exception {
        String topic = "边界人数用例-" + expertCount;
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("PROPOSE", expertCount)));

        MvcResult created = createDraft(createBody(topic, expertCount));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode participants = dataOf(created).get("participants");
        assertThat(participants).hasSize(expertCount + 1);
        assertThat(gateway.calls(topic)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7})
    @DisplayName("本地人数越界直接 400，不调用模型")
    void rejectsOutOfRangeExpertCountLocally(int expertCount) throws Exception {
        String topic = "越界人数用例-" + expertCount;

        MvcResult result = createDraft(createBody(topic, expertCount));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(400);
        assertThat(gateway.totalCalls()).isZero();
        assertThat(count("SELECT COUNT(*) FROM discussion WHERE topic = ?", topic)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "duplicateName",
            "hostWithPerspective",
            "missingHost",
            "multipleHosts",
            "duplicatePerspective",
            "invalidPerspective",
            "missingField",
            "unknownField"
    })
    @DisplayName("模型输出的结构或语义错误补偿一次后仍失败，且不留下任何记录")
    void rejectsInvalidModelRoster(String variant) throws Exception {
        String topic = "非法阵容用例-" + variant;
        ObjectNode node = broken(variant);
        gateway.script(topic,
                StubModelGateway.Step.json(RosterFixtures.json(node)),
                StubModelGateway.Step.json(RosterFixtures.json(node)));
        int discussions = count("SELECT COUNT(*) FROM discussion");
        int participants = count("SELECT COUNT(*) FROM participant");
        int events = count("SELECT COUNT(*) FROM discussion_event");

        MvcResult result = createDraft(createBody(topic));

        assertThat(gateway.calls(topic)).isEqualTo(2);
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(502);
        assertThat(errorOf(result).get("data").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
        assertThat(count("SELECT COUNT(*) FROM participant")).isEqualTo(participants);
        assertThat(count("SELECT COUNT(*) FROM discussion_event")).isEqualTo(events);
    }

    @Test
    @DisplayName("上游持续超时，补偿耗尽后返回 504")
    void mapsTimeoutToB9002() throws Exception {
        String topic = "超时用例";
        gateway.script(topic, StubModelGateway.Step.timeout(), StubModelGateway.Step.timeout());
        int discussions = count("SELECT COUNT(*) FROM discussion");

        MvcResult result = createDraft(createBody(topic));

        assertThat(gateway.calls(topic)).isEqualTo(2);
        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(504);
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
    }

    @Test
    @DisplayName("内容拦截不进入补偿，直接 502")
    void mapsContentBlockWithoutRepair() throws Exception {
        String topic = "内容拦截用例";
        gateway.script(topic, StubModelGateway.Step.blocked(), StubModelGateway.Step.json(RosterFixtures.valid(4)));
        int discussions = count("SELECT COUNT(*) FROM discussion");

        MvcResult result = createDraft(createBody(topic));

        assertThat(gateway.calls(topic)).isEqualTo(1);
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(502);
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
    }

    @Test
    @DisplayName("模型配置缺失返回 503，且不写库")
    void mapsMissingConfiguration() throws Exception {
        String topic = "缺配置用例";
        gateway.script(topic, StubModelGateway.Step.configMissing());

        MvcResult result = createDraft(createBody(topic));

        assertThat(gateway.calls(topic)).isEqualTo(1);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(503);
        assertThat(count("SELECT COUNT(*) FROM discussion WHERE topic = ?", topic)).isZero();
    }

    @Test
    @DisplayName("议题为空白、过短或超过 120 码点时 400 且不调用模型")
    void rejectsInvalidTopic() throws Exception {
        List<String> bodies = List.of(
                "{\"topic\":\"\"}",
                "{\"topic\":\"两字\"}",
                "{\"topic\":\"" + "😀".repeat(121) + "\"}",
                "{}");

        for (String body : bodies) {
            MvcResult result = createDraft(body);
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorOf(result).get("code").intValue()).isEqualTo(400);
        }
        assertThat(gateway.totalCalls()).isZero();
    }

    private ObjectNode broken(String variant) {
        ObjectNode node = RosterFixtures.base("EVALUATE", 4);
        switch (variant) {
            case "duplicateName" -> RosterFixtures.expert(node, 2).put("displayName",
                    RosterFixtures.expert(node, 1).get("displayName").asText() + " ");
            case "hostWithPerspective" -> RosterFixtures.host(node).put("perspective", "COST");
            case "missingHost" -> node.withArray("participants").remove(0);
            case "multipleHosts" -> RosterFixtures.expert(node, 1)
                    .put("role", "HOST").put("stance", (String) null).put("perspective", (String) null);
            case "duplicatePerspective" -> RosterFixtures.expert(node, 2).put("perspective",
                    RosterFixtures.expert(node, 1).get("perspective").asText());
            case "invalidPerspective" -> RosterFixtures.expert(node, 1).put("perspective", "MAGIC");
            case "missingField" -> RosterFixtures.expert(node, 1).remove("title");
            case "unknownField" -> RosterFixtures.expert(node, 1).put("id", "model-owned-id");
            default -> throw new IllegalArgumentException(variant);
        }
        return node;
    }
}