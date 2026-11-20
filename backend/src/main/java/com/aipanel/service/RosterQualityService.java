package com.aipanel.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aipanel.gateway.RosterOutput;
import com.aipanel.gateway.RosterParticipant;
import com.aipanel.gateway.ValidationCodes;

/**
 * 阵容质量校验（Q01 在线确定性部分）。
 *
 * <p>只处理可确定复现的低质量信号：空泛职业与头衔组合、规范化后重复的立场以及只含占位语的立场。
 * 近义语义判断不在此处，交由后续离线评估。
 */
@Component
public class RosterQualityService {

    private static final Set<String> GENERIC_ROLES = Set.of(
            "专家", "行业专家", "资深专家", "领域专家", "顾问", "资深顾问", "行业顾问", "研究者", "分析师");
    private static final Set<String> PLACEHOLDERS = Set.of(
            "具体问题具体分析",
            "需要综合考虑各方面因素",
            "视情况而定",
            "要结合实际情况判断");

    public List<String> validate(RosterOutput output) {
        if (output.participants() == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        for (RosterParticipant member : output.participants()) {
            if (!"EXPERT".equals(member.role())) {
                continue;
            }
            if (GENERIC_ROLES.contains(normalize(member.profession())) && GENERIC_ROLES.contains(normalize(member.title()))) {
                return List.of(ValidationCodes.LOW_QUALITY);
            }
            String stance = normalize(member.stance());
            if (stance.isEmpty()) {
                continue;
            }
            if (PLACEHOLDERS.contains(stance) || !seen.add(stance)) {
                return List.of(ValidationCodes.LOW_QUALITY);
            }
        }
        return List.of();
    }

    private String normalize(String stance) {
        if (stance == null) {
            return "";
        }
        String normalized = Normalizer.normalize(stance, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> Character.isLetterOrDigit(codePoint))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }
}
