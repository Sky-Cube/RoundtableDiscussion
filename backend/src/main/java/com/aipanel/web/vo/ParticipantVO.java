package com.aipanel.web.vo;

/** 公开成员对象。主持的 stance、perspective 为 null。 */
public record ParticipantVO(
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
