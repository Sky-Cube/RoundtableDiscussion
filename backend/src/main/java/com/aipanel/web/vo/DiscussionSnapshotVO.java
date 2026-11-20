package com.aipanel.web.vo;

import java.util.List;

/** 房间完整快照。草稿的 transcript、insights 为 []，两个游标为 0。 */
public record DiscussionSnapshotVO(
        String id,
        String topic,
        int expertCount,
        String discussionGoal,
        String status,
        String startedAt,
        String endedAt,
        Integer errorCode,
        List<ParticipantVO> participants,
        List<UtteranceVO> transcript,
        String insightStatus,
        int insightsThroughUtteranceSeq,
        List<InsightVO> insights,
        String summary,
        int lastEventSeq) {
}
