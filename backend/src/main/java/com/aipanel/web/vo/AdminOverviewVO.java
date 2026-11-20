package com.aipanel.web.vo;

import java.util.Map;

/** 管理台总览：各状态房间数量与全库记录量。 */
public record AdminOverviewVO(Map<String, Integer> discussionCounts, int totalDiscussions, int presetCount,
        int utteranceCount, int insightCount, int eventCount) {
}