package com.aipanel.support;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aipanel.config.FaultInjector;
import com.aipanel.repository.DiscussionEventRepository;
import com.aipanel.util.Ids;

/**
 * 测试专用故障注入：在讨论行与成员行写入之后抛出异常，用于验证整个事务回滚。
 * 生产 profile 不加载本类，打包产物中也不存在。
 */
@Component
@Profile("test")
public class TestFaultInjector implements FaultInjector {

    public enum Mode {
        /** 不注入故障。 */
        NONE,
        /** 成员写入后直接抛出。 */
        PARTICIPANTS,
        /** 先写入一条合成事件再抛出。 */
        PARTICIPANTS_WITH_EVENT,
        SUMMARY,
        INSIGHTS
    }

    private final DiscussionEventRepository events;
    private volatile Mode mode = Mode.NONE;

    public TestFaultInjector(DiscussionEventRepository events) {
        this.events = events;
    }

    public void mode(Mode value) {
        this.mode = value;
    }

    public void reset() {
        this.mode = Mode.NONE;
    }

    @Override
    public void afterParticipantsInserted(String discussionId, int participantCount) {
        Mode current = mode;
        if (current != Mode.PARTICIPANTS && current != Mode.PARTICIPANTS_WITH_EVENT) {
            return;
        }
        if (current == Mode.PARTICIPANTS_WITH_EVENT) {
            events.insert(discussionId, 1, "discussion.status", "{}", Ids.now());
        }
        throw new FaultInjectionException();
    }

    @Override
    public void afterSummaryAndCompletion(String discussionId) {
        if (mode == Mode.SUMMARY) {
            throw new FaultInjectionException();
        }
    }

    @Override
    public void afterInsightProjection(String discussionId) {
        if (mode == Mode.INSIGHTS) {
            throw new FaultInjectionException();
        }
    }
}
