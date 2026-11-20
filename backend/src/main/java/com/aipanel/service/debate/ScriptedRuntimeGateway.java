package com.aipanel.service.debate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Conditional;

import com.aipanel.config.GatewayConditions;
import org.springframework.stereotype.Component;

import com.aipanel.gateway.RuntimeGateway;
import com.aipanel.gateway.RuntimeTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 本地合成回包，仅供开发及可重复测试。 */
@Component
@Profile({"dev", "test"})
@Conditional(GatewayConditions.Scripted.class)
public class ScriptedRuntimeGateway implements RuntimeGateway {

    private final ObjectMapper mapper;

    public ScriptedRuntimeGateway(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String generate(RuntimeTask task, Map<String, Object> input, List<String> codes, Instant deadline) {
        JsonNode data = mapper.valueToTree(input);
        List<JsonNode> experts = data.path("utterances").valueStream()
                .filter(line -> "EXPERT".equals(line.path("kind").asText())).toList();
        int turn = experts.size() + 1;
        ObjectNode result = switch (task) {
            case R02 -> mapper.createObjectNode().put("intent", "RAISE").putNull("targetUtteranceSeq")
                    .put("focusCode", "TRADE_OFF").put("proposedClaim", "应核对第" + turn + "项验证条件。");
            case R03 -> sentences("建议对第" + turn + "项条件做小流量验证。", "本轮应保留验收口径并记录试点数据。");
            case R04 -> insights(experts);
            case R05 -> sentences("今天围绕题设的具体范围展开讨论。", "请各位说明各自的条件与证据出处。");
            case R06 -> intervention(experts);
            case R07 -> summary(experts);
            default -> throw new IllegalArgumentException("请通过阵容网关生成角色");
        };
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("脚本输出序列化失败", error);
        }
    }

    private ObjectNode sentences(String first, String second) {
        var result = mapper.createObjectNode();
        result.putArray("sentences").add(first).add(second);
        return result;
    }

    private ObjectNode intervention(List<JsonNode> experts) {
        var result = sentences("请各位检查已有判断成立的条件。", "哪些证据可以核对这些条件？");
        if (experts.isEmpty()) {
            result.putNull("replyToUtteranceSeq");
        } else {
            result.put("replyToUtteranceSeq", experts.getLast().path("seq").asInt());
        }
        return result;
    }

    private ObjectNode summary(List<JsonNode> experts) {
        var result = experts.isEmpty()
                ? sentences("专家发言不足，暂时无法归纳共同意见。", "影响选择的判断条件仍待各方补充。")
                : sentences("已发言专家提出逐项开展小流量验证。", "各项验证的实际结果仍待核对。");
        var evidence = result.putArray("evidenceUtteranceSeqs");
        experts.stream().limit(6).forEach(line -> evidence.add(line.path("seq").asInt()));
        return result;
    }

    private ObjectNode insights(List<JsonNode> experts) {
        var result = mapper.createObjectNode();
        var insights = result.putArray("insights");
        if (experts.isEmpty()) {
            return result;
        }
        JsonNode first = experts.getFirst();
        experts.stream().filter(line -> !line.path("participantId").equals(first.path("participantId")))
                .findFirst().ifPresent(other -> {
                    addInsight(insights, "试验应保留验收口径", "本轮应保留验收口径", List.of(first, other));
                    addInsight(insights, "试点过程应记录数据", "记录试点数据", List.of(first, other));
                });
        return result;
    }

    private void addInsight(com.fasterxml.jackson.databind.node.ArrayNode insights, String text, String quote,
            List<JsonNode> sources) {
        var evidence = insights.addObject().put("kind", "CONSENSUS").put("text", text).putArray("evidence");
        for (JsonNode source : sources) {
            evidence.addObject().put("utteranceSeq", source.path("seq").asInt()).put("quote", quote);
        }
    }
}
