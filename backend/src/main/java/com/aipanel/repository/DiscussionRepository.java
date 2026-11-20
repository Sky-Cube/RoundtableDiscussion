package com.aipanel.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.aipanel.model.Discussion;

/** 房间行仓储。全部语句参数化，房间查询以主键或状态限定。 */
@Repository
public class DiscussionRepository {

    private static final String COLUMNS = "id, topic, discussion_goal, expert_count, status, last_event_seq, "
            + "insight_status, insights_through_utterance_seq, created_at, started_at, ended_at, error_code";

    private static final RowMapper<Discussion> MAPPER = (rows, index) -> new Discussion(
            rows.getString("id"),
            rows.getString("topic"),
            rows.getString("discussion_goal"),
            rows.getInt("expert_count"),
            rows.getString("status"),
            rows.getInt("last_event_seq"),
            rows.getString("insight_status"),
            nullableInt(rows, "insights_through_utterance_seq"),
            rows.getString("created_at"),
            rows.getString("started_at"),
            rows.getString("ended_at"),
            rows.getString("error_code"));

    private final JdbcTemplate jdbcTemplate;

    public DiscussionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** sqlite-jdbc 对 SQL NULL 调用 getObject(col, Integer.class) 会报错，这里统一按值读取。 */
    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }

    public void insert(Discussion discussion) {
        jdbcTemplate.update("INSERT INTO discussion (" + COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                discussion.id(), discussion.topic(), discussion.discussionGoal(), discussion.expertCount(),
                discussion.status(), discussion.lastEventSeq(), discussion.insightStatus(),
                discussion.insightsThroughUtteranceSeq(), discussion.createdAt(), discussion.startedAt(),
                discussion.endedAt(), discussion.errorCode());
    }

    public Optional<Discussion> findById(String id) {
        List<Discussion> rows = jdbcTemplate.query("SELECT " + COLUMNS + " FROM discussion WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 大厅只展示进行中和总结中的房间。 */
    public List<Discussion> findActive() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM discussion "
                + "WHERE status IN ('RUNNING','SUMMARIZING') ORDER BY started_at DESC, id ASC", MAPPER);
    }

    /** 管理台展示全部房间，可按状态过滤。 */
    public List<Discussion> findAll(String status) {
        if (status == null || status.isBlank() || "ALL".equals(status)) {
            return jdbcTemplate.query("SELECT " + COLUMNS + " FROM discussion "
                    + "ORDER BY created_at DESC, id ASC", MAPPER);
        }
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM discussion WHERE status = ? "
                + "ORDER BY created_at DESC, id ASC", MAPPER, status);
    }

    public int countByStatus(String status) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM discussion WHERE status = ?", Integer.class,
                status);
        return count == null ? 0 : count;
    }

    public int countActive() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM discussion WHERE status IN ('RUNNING','SUMMARIZING')", Integer.class);
        return count == null ? 0 : count;
    }

    /** 条件更新：只有仍处于 DRAFT 的房间能进入 RUNNING，用于并发确认去重。 */
    public boolean markRunning(String id, String startedAt) {
        return jdbcTemplate.update("UPDATE discussion SET status = 'RUNNING', started_at = ? "
                + "WHERE id = ? AND status = 'DRAFT'", startedAt, id) == 1;
    }

    public boolean markSummarizing(String id) {
        return jdbcTemplate.update("UPDATE discussion SET status = 'SUMMARIZING' "
                + "WHERE id = ? AND status = 'RUNNING'", id) == 1;
    }

    public boolean markCompleted(String id, String endedAt) {
        return jdbcTemplate.update("UPDATE discussion SET status = 'COMPLETED', ended_at = ? "
                + "WHERE id = ? AND status = 'SUMMARIZING'", endedAt, id) == 1;
    }

    public boolean markFailed(String id, String endedAt, String errorCode) {
        return jdbcTemplate.update("UPDATE discussion SET status = 'FAILED', ended_at = ?, error_code = ? "
                + "WHERE id = ? AND status IN ('RUNNING','SUMMARIZING')", endedAt, errorCode, id) == 1;
    }

    /** 先取得写锁并验证内容版本，避免从 SQLite 已过期的读快照升级为写事务。 */
    public boolean lockVersion(String id, String status, int utteranceSeq, int insightEventSeq) {
        return jdbcTemplate.update("""
                UPDATE discussion SET last_event_seq = last_event_seq
                 WHERE id = ? AND status = ?
                   AND ? = (SELECT COALESCE(MAX(seq), 0) FROM utterance WHERE discussion_id = discussion.id)
                   AND ? = (SELECT COALESCE(MAX(seq), 0) FROM discussion_event
                             WHERE discussion_id = discussion.id AND type = 'insight.updated')
                """, id, status, utteranceSeq, insightEventSeq) == 1;
    }

    /** 原子推进事件游标并返回新值，避免多写者拿到相同序号。 */
    public int nextEventSeq(String id) {
        Integer seq = jdbcTemplate.queryForObject(
                "UPDATE discussion SET last_event_seq = last_event_seq + 1 WHERE id = ? RETURNING last_event_seq",
                Integer.class, id);
        return seq == null ? 0 : seq;
    }

    public void updateInsightProjection(String id, String insightStatus, Integer throughUtteranceSeq) {
        jdbcTemplate.update("UPDATE discussion SET insight_status = ?, insights_through_utterance_seq = ? WHERE id = ?",
                insightStatus, throughUtteranceSeq, id);
    }

    /** 进程重启时把未收尾的房间标记为失败，保留已公开内容。 */
    public List<Discussion> findUnfinished() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM discussion "
                + "WHERE status IN ('RUNNING','SUMMARIZING')", MAPPER);
    }
}
