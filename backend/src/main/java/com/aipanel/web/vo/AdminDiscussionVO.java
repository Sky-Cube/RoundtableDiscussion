package com.aipanel.web.vo;

/** 管理台房间行：状态、时间与各表记录数。 */
public record AdminDiscussionVO(String id, String topic, String discussionGoal, int expertCount, String status,
        String createdAt, String startedAt, String endedAt, Integer errorCode, int lastEventSeq,
        int participantCount, int utteranceCount, int insightCount, int eventCount) {
}
