package com.aipanel.model;

import java.util.List;

/** 预设行及其完整阵容。 */
public record Preset(String slug, String topic, String discussionGoal, int expertCount,
        List<PresetParticipant> participants) {
}
