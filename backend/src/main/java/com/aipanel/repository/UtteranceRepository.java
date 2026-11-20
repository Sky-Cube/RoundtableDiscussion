package com.aipanel.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.aipanel.model.Utterance;

/** 实录行仓储。发言序号在同一房间内递增。 */
@Repository
public class UtteranceRepository {

    private static final RowMapper<Utterance> MAPPER = (rows, index) -> new Utterance(
            rows.getInt("seq"),
            rows.getString("participant_id"),
            rows.getString("kind"),
            rows.getString("content"),
            nullableInt(rows, "reply_to_utterance_seq"),
            rows.getString("created_at"));

    private final JdbcTemplate jdbcTemplate;

    public UtteranceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Integer nullableInt(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }

    public List<Utterance> findByDiscussionId(String discussionId) {
        return jdbcTemplate.query("SELECT seq, participant_id, kind, content, reply_to_utterance_seq, created_at "
                + "FROM utterance WHERE discussion_id = ? ORDER BY seq ASC", MAPPER, discussionId);
    }

    public int nextSeq(String discussionId) {
        Integer seq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM utterance WHERE discussion_id = ?", Integer.class, discussionId);
        return seq == null ? 1 : seq;
    }

    public void insert(String discussionId, Utterance utterance) {
        jdbcTemplate.update("INSERT INTO utterance (discussion_id, seq, participant_id, kind, content, "
                        + "reply_to_utterance_seq, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                discussionId, utterance.seq(), utterance.participantId(), utterance.kind(), utterance.content(),
                utterance.replyToUtteranceSeq(), utterance.createdAt());
    }

    public int countByDiscussion(String discussionId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM utterance WHERE discussion_id = ?",
                Integer.class, discussionId);
        return count == null ? 0 : count;
    }

    public int countExpertsSpoken(String discussionId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT participant_id) FROM utterance "
                + "WHERE discussion_id = ? AND kind = 'EXPERT'", Integer.class, discussionId);
        return count == null ? 0 : count;
    }
}
