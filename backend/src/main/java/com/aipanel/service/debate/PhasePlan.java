package com.aipanel.service.debate;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PhasePlan(
        @JsonProperty(value = "phase", required = true) String id,
        @JsonProperty(value = "maxExpertTurns", required = true) int turns,
        @JsonProperty(value = "maxWindows", required = true) int windows,
        @JsonProperty(value = "preferredFocusCodes", required = true) List<String> focus,
        @JsonProperty("reviewBeforeSummary") Boolean reviewBeforeSummary) {

    private static final Map<String, String> FOCUS_LABELS = Map.of(
            "TRADE_OFF", "关注关键取舍", "ASSUMPTION", "检验前提假设", "EVIDENCE", "关注证据依据",
            "RISK", "关注风险与边界条件", "IMPACT", "关注影响范围", "IMPLEMENTATION", "关注实施路径");

    public PhasePlan {
        focus = List.copyOf(focus);
    }

    public static String focusLabel(String code) {
        return FOCUS_LABELS.get(code);
    }
}
