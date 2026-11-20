package com.aipanel.gateway;

/** 模型返回的单个成员。主持的 stance 与 perspective 必须为 null。 */
public record RosterParticipant(String role, String displayName, String profession, String title, String stance,
        String perspective) {
}
