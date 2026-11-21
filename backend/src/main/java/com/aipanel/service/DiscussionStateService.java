package com.aipanel.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aipanel.config.FaultInjector;
import com.aipanel.exception.ApiErrorCode;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.model.Participant;
import com.aipanel.model.Utterance;
import com.aipanel.repository.DiscussionEventRepository;
import com.aipanel.repository.DiscussionRepository;
import com.aipanel.repository.InsightRepository;
import com.aipanel.repository.ParticipantRepository;
import com.aipanel.repository.UtteranceRepository;
import com.aipanel.service.debate.DiscussionContext;
import com.aipanel.util.Ids;

@Service
public class DiscussionStateService {

    private final DiscussionRepository discussions;
    private final ParticipantRepository participants;
    private final UtteranceRepository utterances;
    private final InsightRepository insights;
    private final DiscussionEventRepository events;
    private final DiscussionEventPublisher publisher;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final FaultInjector faults;
    private final Clock clock;

    public DiscussionStateService(DiscussionRepository discussions, ParticipantRepository participants,
            UtteranceRepository utterances, InsightRepository insights, DiscussionEventRepository events,
            DiscussionEventPublisher publisher, TransactionTemplate transactions, FaultInjector faults, Clock clock) {
        this.discussions = discussions;
        this.participants = participants;
        this.utterances = utterances;
        this.insights = insights;
        this.events = events;
        this.publisher = publisher;
        this.writes = transactions;
        this.reads = new TransactionTemplate(transactions.getTransactionManager());
        this.reads.setReadOnly(true);
        this.faults = faults;
        this.clock = clock;
    }

    public DiscussionContext snapshot(String id) {
        return reads.execute(transaction -> new DiscussionContext(discussions.findById(id).orElseThrow(),
                participants.findByDiscussionId(id), utterances.findByDiscussionId(id),
                insights.findByDiscussionId(id), events.latestInsightSeq(id)));
    }

    public void stage(DiscussionContext context, Participant member, String status, String focus, Instant deadline) {
        String id = context.room().id();
        writes.executeWithoutResult(transaction -> {
            if (!discussions.lockVersion(id, context.room().status(), context.version(), context.insightEventSeq())) {
                return;
            }
            if (deadline != null) {
                checkDeadline(deadline);
            }
            stage(id, member, status, focus);
        });
    }

    public void speak(DiscussionContext context, Participant member, String kind, String text, Integer reply,
            Instant deadline) {
        String id = context.room().id();
        writes.executeWithoutResult(transaction -> {
            if (!discussions.lockVersion(id, context.room().status(), context.version(), context.insightEventSeq())) {
                throw expired();
            }
            checkDeadline(deadline);
            int seq = context.version() + 1;
            String createdAt = Ids.now();
            utterances.insert(id, new Utterance(seq, member.id(), kind, text, reply, createdAt));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("seq", seq);
            payload.put("participantId", member.id());
            payload.put("kind", kind);
            payload.put("content", text);
            payload.put("replyToUtteranceSeq", reply);
            payload.put("createdAt", createdAt);
            publisher.publish(id, "utterance.added", payload);
            stage(id, member, "IDLE", null);
            if ("SUMMARY".equals(kind)) {
                if (!discussions.markCompleted(id, Ids.now())) {
                    throw expired();
                }
                publisher.publishStatus(id);
                faults.afterSummaryAndCompletion(id);
            }
        });
    }

    public void summarizing(String id) {
        writes.executeWithoutResult(transaction -> {
            if (!discussions.markSummarizing(id)) {
                throw expired();
            }
            publisher.publishStatus(id);
        });
    }

    /** 所有单例初始化后、Web 服务接收请求前，收尾上次进程遗留的房间。 */
    public void recoverInterrupted() {
        for (String id : discussions.findUnfinishedIds()) {
            fail(id, ApiErrorCode.B0005.name());
        }
    }

    public void fail(String id, String code) {
        writes.executeWithoutResult(transaction -> {
            if (discussions.markFailed(id, Ids.now(), code)) {
                participants.findByDiscussionId(id).stream().filter(member -> !"IDLE".equals(member.stageStatus()))
                        .forEach(member -> stage(id, member, "IDLE", null));
                publisher.publishStatus(id);
            }
        });
    }

    private void stage(String id, Participant member, String status, String focus) {
        participants.updateStage(id, member.id(), status, focus);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("participantId", member.id());
        payload.put("stageStatus", status);
        payload.put("publicFocus", focus);
        publisher.publish(id, "participant.status", payload);
    }

    private void checkDeadline(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) {
            throw expired();
        }
    }

    private static ModelGatewayException expired() {
        return new ModelGatewayException(ModelGatewayException.Kind.STALE_RESPONSE, "讨论步骤已过期");
    }
}
