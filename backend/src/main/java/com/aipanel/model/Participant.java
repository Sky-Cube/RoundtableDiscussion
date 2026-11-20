package com.aipanel.model;

/** 房间成员行。主持的 stance 与 perspective 恒为 null。 */
public record Participant(
        String discussionId,
        String id,
        String role,
        String displayName,
        String profession,
        String title,
        String stance,
        String perspective,
        String colorKey,
        String stageStatus,
        String publicFocus) {
}
