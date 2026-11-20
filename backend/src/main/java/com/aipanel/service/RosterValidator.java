package com.aipanel.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aipanel.gateway.RosterOutput;
import com.aipanel.gateway.RosterParticipant;
import com.aipanel.gateway.ValidationCodes;
import com.aipanel.util.TopicNormalizer;

/** 阵容结构与业务校验。返回校验码集合，空集合表示通过。 */
@Component
public class RosterValidator {

    public static final int MAX_EXPERT_COUNT = 6;
    public static final List<String> GOALS = List.of("EVALUATE", "PROPOSE", "SOLVE", "EXPLORE");
    public static final List<String> PERSPECTIVES =
            List.of("EVIDENCE", "DELIVERY", "COST", "RISK", "USER_IMPACT", "GOVERNANCE");

    public List<String> validate(RosterOutput output, int expertCount) {
        Set<String> codes = new LinkedHashSet<>();
        String goal = output.discussionGoal();
        if (goal == null || goal.isBlank()) {
            codes.add(ValidationCodes.GOAL_MISSING);
        } else if (!GOALS.contains(goal)) {
            codes.add(ValidationCodes.GOAL_INVALID);
        }

        List<RosterParticipant> participants = output.participants();
        if (participants == null || participants.size() != expertCount + 1) {
            codes.add(ValidationCodes.PARTICIPANT_COUNT);
        }
        if (participants == null || participants.isEmpty()) {
            return List.copyOf(codes);
        }
        if (!"HOST".equals(participants.get(0).role())) {
            codes.add(ValidationCodes.HOST_NOT_FIRST);
        }
        long hosts = participants.stream().filter(member -> "HOST".equals(member.role())).count();
        if (hosts != 1) {
            codes.add(ValidationCodes.HOST_COUNT);
        }

        Set<String> perspectives = new HashSet<>();
        Set<String> names = new HashSet<>();
        int experts = 0;
        for (RosterParticipant member : participants) {
            if ("HOST".equals(member.role())) {
                if (member.stance() != null || member.perspective() != null) {
                    codes.add(ValidationCodes.HOST_FIELD_INVALID);
                }
            } else if ("EXPERT".equals(member.role())) {
                experts++;
                if (member.perspective() == null || !PERSPECTIVES.contains(member.perspective())) {
                    codes.add(ValidationCodes.PERSPECTIVE_INVALID);
                } else if (!perspectives.add(member.perspective())) {
                    codes.add(ValidationCodes.PERSPECTIVE_DUPLICATE);
                }
                if (!validStance(member.stance())) {
                    codes.add(ValidationCodes.STANCE_INVALID);
                }
            } else {
                codes.add(ValidationCodes.ROLE_INVALID);
            }

            if (!within(member.displayName(), 2, 20)
                    || !within(member.profession(), 2, 32)
                    || !within(member.title(), 2, 40)) {
                codes.add(ValidationCodes.FIELD_LENGTH_INVALID);
            }
            String name = normalizeName(member.displayName());
            if (!name.isEmpty() && !names.add(name)) {
                codes.add(ValidationCodes.NAME_DUPLICATE);
            }
        }
        if (experts != expertCount) {
            codes.add(ValidationCodes.PARTICIPANT_COUNT);
        }
        return List.copyOf(codes);
    }

    private boolean validStance(String stance) {
        if (stance == null || stance.isBlank()) {
            return false;
        }
        int length = TopicNormalizer.codePointLength(stance.trim());
        return length >= 8 && length <= 100;
    }

    private boolean within(String value, int min, int max) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int length = TopicNormalizer.codePointLength(value.trim());
        return length >= min && length <= max;
    }

    private String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
}
