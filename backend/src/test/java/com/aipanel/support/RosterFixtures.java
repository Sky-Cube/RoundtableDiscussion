package com.aipanel.support;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 合成 rosterOutput 样例：可返回合法阵容，也可对任意字段做定向破坏。 */
public final class RosterFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> PERSPECTIVES = List.of("COST", "DELIVERY", "EVIDENCE", "RISK", "USER_IMPACT", "GOVERNANCE");

    private RosterFixtures() {
    }

    /** 合法阵容：一位主持 + experts 位专家，立场与视角均不重复。 */
    public static ObjectNode base(String goal, int experts) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("discussionGoal", goal);
        ArrayNode participants = root.putArray("participants");
        ObjectNode host = participants.addObject();
        host.put("role", "HOST");
        host.put("displayName", "顾言");
        host.put("profession", "节目主持");
        host.put("title", "圆桌主持人");
        host.putNull("stance");
        host.putNull("perspective");

        String[] names = {"陈简", "林昭", "赵岚", "方钰", "苏晓", "秦宜"};
        for (int index = 0; index < experts; index++) {
            ObjectNode expert = participants.addObject();
            expert.put("role", "EXPERT");
            expert.put("displayName", names[index]);
            expert.put("profession", "专业领域" + (index + 1));
            expert.put("title", "研究员" + (index + 1));
            expert.put("stance", "主张" + (index + 1) + "：按明确条件推进并保留退出条款");
            expert.put("perspective", PERSPECTIVES.get(index));
        }
        return root;
    }

    public static String json(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    public static String valid(String goal, int experts) {
        return json(base(goal, experts));
    }

    public static String valid(int experts) {
        return valid("EVALUATE", experts);
    }

    /** 两位专家立场完全相同，用于触发 LOW_QUALITY。 */
    public static String duplicateStance(String stance, int experts) {
        ObjectNode root = base("EVALUATE", experts);
        for (int index = 1; index < root.withArray("participants").size(); index++) {
            ((ObjectNode) root.withArray("participants").get(index)).put("stance", stance);
        }
        return json(root);
    }

    public static ObjectNode expert(ObjectNode root, int expertIndex) {
        return (ObjectNode) root.withArray("participants").get(expertIndex);
    }

    public static ObjectNode host(ObjectNode root) {
        return (ObjectNode) root.withArray("participants").get(0);
    }
}