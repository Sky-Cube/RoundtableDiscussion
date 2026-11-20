package com.aipanel.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.aipanel.exception.ApiErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 公开事件行仓储：SSE 回放与管理台事件流都从这里读取。 */
@Repository
public class DiscussionEventRepository {

    private static final RowMapper<EventRow> MAPPER = (rows, index) -> new EventRow(
            rows.getInt("seq"),
            rows.getString("type"),
            rows.getString("payload_json"),
            rows.getString("created_at"));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DiscussionEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(String discussionId, int seq, String type, String payloadJson, String createdAt) {
        jdbcTemplate.update("INSERT INTO discussion_event (discussion_id, seq, type, payload_json, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", discussionId, seq, type, payloadJson, createdAt);
    }

    public int countByDiscussion(String discussionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM discussion_event WHERE discussion_id = ?", Integer.class, discussionId);
        return count == null ? 0 : count;
    }

    public List<EventRow> findAfter(String discussionId, int after) {
        return jdbcTemplate.query("SELECT seq, type, payload_json, created_at FROM discussion_event "
                + "WHERE discussion_id = ? AND seq > ? ORDER BY seq ASC", MAPPER, discussionId, after)
                .stream().map(this::withPublicStatusCode).toList();
    }

    private EventRow withPublicStatusCode(EventRow event) {
        if (!"discussion.status".equals(event.type())) {
            return event;
        }
        try {
            var envelope = objectMapper.readTree(event.payloadJson());
            if (envelope.path("payload") instanceof ObjectNode payload && payload.path("errorCode").isTextual()) {
                payload.put("errorCode", ApiErrorCode.fromStoredCode(payload.get("errorCode").asText()));
                return new EventRow(event.seq(), event.type(), objectMapper.writeValueAsString(envelope),
                        event.createdAt());
            }
            return event;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("公开状态事件格式错误", error);
        }
    }

    public int latestInsightSeq(String discussionId) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM discussion_event "
                + "WHERE discussion_id = ? AND type = 'insight.updated'", Integer.class, discussionId);
    }

    public int maxSeq(String discussionId) {
        Integer seq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM discussion_event WHERE discussion_id = ?", Integer.class,
                discussionId);
        return seq == null ? 0 : seq;
    }

    /** 一条已提交的公开事件。 */
    public record EventRow(int seq, String type, String payloadJson, String createdAt) {
    }
}
