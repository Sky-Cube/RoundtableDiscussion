package com.aipanel.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.aipanel.config.PanelProperties;

/** 讨论执行槽位：先占位再落库，避免线程池拒绝时留下无法执行的 RUNNING 房间。 */
@Component
public class DiscussionRunner {

    private final DiscussionOrchestrator orchestrator;
    private final ThreadPoolTaskExecutor executor;
    private final Semaphore slots;

    public DiscussionRunner(DiscussionOrchestrator orchestrator,
            @Qualifier("discussionExecutor") ThreadPoolTaskExecutor discussionExecutor,
            PanelProperties properties) {
        this.orchestrator = orchestrator;
        this.executor = discussionExecutor;
        this.slots = new Semaphore(properties.orchestration().capacity());
    }

    /** 占用一个执行槽位并异步开跑；无槽位返回 false。 */
    public boolean submit(String discussionId, Runnable commitStart) {
        if (!slots.tryAcquire()) {
            return false;
        }
        CompletableFuture<Boolean> committed = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    if (committed.join()) {
                        orchestrator.run(discussionId);
                    }
                } finally {
                    slots.release();
                }
            });
        } catch (TaskRejectedException error) {
            slots.release();
            return false;
        }
        try {
            commitStart.run();
            committed.complete(true);
            return true;
        } finally {
            committed.complete(false);
        }
    }

    public int availableSlots() {
        return slots.availablePermits();
    }
}
