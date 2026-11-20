package com.aipanel.web.vo;

import java.util.List;

/** 预设对象：议题、讨论目标与完整阵容。 */
public record PresetVO(String slug, String topic, String discussionGoal, int expertCount,
        List<PresetParticipantVO> participants) {
}
