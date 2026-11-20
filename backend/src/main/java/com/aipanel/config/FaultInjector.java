package com.aipanel.config;

/**
 * 事务内故障注入钩子。仅测试 profile 提供实现；生产为无操作，打包产物中不含注入逻辑。
 */
public interface FaultInjector {

    /** 在讨论行与成员行写入之后调用，默认无操作。 */
    void afterParticipantsInserted(String discussionId, int participantCount);

    default void afterSummaryAndCompletion(String discussionId) {
    }

    default void afterInsightProjection(String discussionId) {
    }
}
