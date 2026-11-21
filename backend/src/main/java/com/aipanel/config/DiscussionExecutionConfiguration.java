package com.aipanel.config;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.aipanel.service.DiscussionStateService;
import com.aipanel.service.RosterValidator;
import com.aipanel.service.debate.DiscussionWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 讨论执行线程池：容量与并发房间数一致，队列满即拒绝，由调用方映射为 429。 */
@Configuration
public class DiscussionExecutionConfiguration {

    @Bean
    public Clock discussionClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SmartInitializingSingleton interruptedDiscussionRecovery(DiscussionStateService state) {
        return state::recoverInterrupted;
    }

    @Bean
    public DiscussionWorkflow discussionWorkflow(PanelProperties properties, ObjectMapper mapper) throws IOException {
        return DiscussionWorkflow.load(properties.gateway().templateDirectory(), mapper);
    }

    @Bean(name = "modelExecutor")
    public ThreadPoolTaskExecutor modelExecutor(PanelProperties properties) {
        int slots = Math.multiplyExact(properties.orchestration().capacity(), RosterValidator.MAX_EXPERT_COUNT);
        return executor(slots, slots, "panel-model-");
    }

    @Bean(name = "discussionExecutor")
    public ThreadPoolTaskExecutor discussionExecutor(PanelProperties properties) {
        int capacity = properties.orchestration().capacity();
        return executor(capacity, capacity, "panel-run-");
    }

    private ThreadPoolTaskExecutor executor(int capacity, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(capacity);
        executor.setMaxPoolSize(capacity);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
