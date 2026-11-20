package com.aipanel.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 生产与默认环境的空实现：草稿创建不会被任何故障注入打断。 */
@Component
@Profile("!test")
public class NoopFaultInjector implements FaultInjector {

    @Override
    public void afterParticipantsInserted(String discussionId, int participantCount) {
        // 生产路径不做任何事。
    }
}
