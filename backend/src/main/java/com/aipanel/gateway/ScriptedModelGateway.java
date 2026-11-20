package com.aipanel.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Conditional;
import com.aipanel.config.GatewayConditions;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 本地联调用的确定性脚本网关。
 *
 * <p>按议题关键字推断讨论目标，用固定角色表拼出符合 Schema 的阵容，不访问任何外部服务。
 * 它只用于本地演示与前后端联调，不代表真实模型质量，也不会出现在生产 profile 中。
 */
@Component
@Profile("dev & !test")
@Conditional(GatewayConditions.Scripted.class)
public class ScriptedModelGateway implements ModelGateway {

    private static final String HOST_NAME = "顾言";
    private static final String HOST_PROFESSION = "节目主持";
    private static final String HOST_TITLE = "圆桌主持人";

    private static final List<Persona> PERSONAS = List.of(
            new Persona("陈简", "成本管理", "成本控制负责人", "COST", "先比较总拥有成本，再决定自研与采购的先后顺序"),
            new Persona("林昭", "软件工程", "平台架构师", "DELIVERY", "关键流程保留自研编排的主动权，避免被单一供应商锁住"),
            new Persona("赵岚", "客服运营", "客户运营经理", "EVIDENCE", "用小流量指标验证首次解决率之后再扩大接入范围"),
            new Persona("方钰", "隐私工程", "隐私工程师", "RISK", "先约束数据留存与迁移权利，再放开对外接入范围"),
            new Persona("苏晓", "用户体验", "体验研究负责人", "USER_IMPACT", "按一线坐席的实际可达性安排改造与培训顺序"),
            new Persona("秦宜", "合规治理", "治理顾问", "GOVERNANCE", "把审批责任与审计口径写进运行规则后再执行"));

    private final ObjectMapper objectMapper;

    public ScriptedModelGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateRoster(String topic, int expertCount, List<String> validationCodes) {
        int count = Math.max(2, Math.min(6, expertCount));
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("role", "HOST");
        host.put("displayName", HOST_NAME);
        host.put("profession", HOST_PROFESSION);
        host.put("title", HOST_TITLE);
        host.put("stance", null);
        host.put("perspective", null);

        List<Map<String, Object>> participants = new ArrayList<>();
        participants.add(host);
        for (int index = 0; index < count; index++) {
            Persona persona = PERSONAS.get(index);
            Map<String, Object> expert = new LinkedHashMap<>();
            expert.put("role", "EXPERT");
            expert.put("displayName", persona.name());
            expert.put("profession", persona.profession());
            expert.put("title", persona.title());
            expert.put("stance", persona.stance());
            expert.put("perspective", persona.perspective());
            participants.add(expert);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("discussionGoal", detectGoal(topic));
        output.put("participants", participants);
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException error) {
            throw new ModelGatewayException(ModelGatewayException.Kind.UPSTREAM_ERROR, "无法序列化合成阵容", error);
        }
    }

    private String detectGoal(String topic) {
        String text = topic == null ? "" : topic;
        if (containsAny(text, "排查", "原因", "整改", "定位", "故障")) {
            return "SOLVE";
        }
        if (containsAny(text, "设计", "方案", "实现", "落地", "搭建")) {
            return "PROPOSE";
        }
        if (containsAny(text, "会如何", "哪些", "影响", "趋势", "未来")) {
            return "EXPLORE";
        }
        return "EVALUATE";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record Persona(String name, String profession, String title, String perspective, String stance) {
    }
}
