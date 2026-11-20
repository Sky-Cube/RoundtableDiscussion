package com.aipanel.model;

/** 预设成员行。 */
public record PresetParticipant(
        String id,
        String role,
        String displayName,
        String profession,
        String title,
        String stance,
        String perspective,
        String colorKey) {
}
