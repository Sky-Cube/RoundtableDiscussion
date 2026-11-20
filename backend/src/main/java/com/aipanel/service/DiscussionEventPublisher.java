package com.aipanel.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.model.Discussion;
import com.aipanel.repository.DiscussionEventRepository;
import com.aipanel.repository.DiscussionRepository;
import com.aipanel.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 公开事件发布器：生成信封字段并在调用方事务内落库。 */
@Component
public class DiscussionEventPublisher {

    private final DiscussionRepository discussions;
    private final DiscussionEventRepository events;
    private final ObjectMapper objectMapper;

    public DiscussionEventPublisher(DiscussionRepository discussions, DiscussionEventRepository events,
            ObjectMapper objectMapper) {
        this.discussions = discussions;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** 分配本房间下一个事件序号并写入事件行；必须在事务内调用。 */
    public int publish(String discussionId, String type, Map<String, Object> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("公开事件必须在业务事务内写入");
        }
        int seq = discussions.nextEventSeq(discussionId);
        String occurredAt = Ids.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("discussionId", discussionId);
        envelope.put("seq", seq);
        envelope.put("occurredAt", occurredAt);
        envelope.put("payload", payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("事件序列化失败", error);
        }
        events.insert(discussionId, seq, type, json, occurredAt);
        return seq;
    }

    public void publishStatus(String discussionId) {
        Discussion room = discussions.findById(discussionId).orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", room.status());
        payload.put("startedAt", room.startedAt());
        payload.put("endedAt", room.endedAt());
        payload.put("errorCode", ApiErrorCode.fromStoredCode(room.errorCode()));
        publish(discussionId, "discussion.status", payload);
    }
}
