package com.aipanel.model;

/** 房间行。草稿阶段 startedAt、endedAt、errorCode 均为 null，lastEventSeq 为 0。 */
public record Discussion(
        String id,
        String topic,
        String discussionGoal,
        int expertCount,
        String status,
        int lastEventSeq,
        String insightStatus,
        Integer insightsThroughUtteranceSeq,
        String createdAt,
        String startedAt,
        String endedAt,
        String errorCode) {
}
