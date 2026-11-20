package com.aipanel.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.aipanel.model.Participant;

/** 成员行仓储。所有语句都以 discussion_id 限定房间范围。 */
@Repository
public class ParticipantRepository {

    private static final RowMapper<Participant> MAPPER = (rows, index) -> new Participant(
            rows.getString("discussion_id"),
            rows.getString("id"),
            rows.getString("role"),
            rows.getString("display_name"),
            rows.getString("profession"),
            rows.getString("title"),
            rows.getString("stance"),
            rows.getString("perspective"),
            rows.getString("color_key"),
            rows.getString("stage_status"),
            rows.getString("public_focus"));

    private final JdbcTemplate jdbcTemplate;

    public ParticipantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(List<Participant> participants) {
        jdbcTemplate.batchUpdate("INSERT INTO participant (discussion_id, id, role, display_name, profession, title, "
                + "stance, perspective, color_key, stage_status, public_focus) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", participants, 50, (statement, participant) -> {
                    statement.setString(1, participant.discussionId());
                    statement.setString(2, participant.id());
                    statement.setString(3, participant.role());
                    statement.setString(4, participant.displayName());
                    statement.setString(5, participant.profession());
                    statement.setString(6, participant.title());
                    statement.setString(7, participant.stance());
                    statement.setString(8, participant.perspective());
                    statement.setString(9, participant.colorKey());
                    statement.setString(10, participant.stageStatus());
                    statement.setString(11, participant.publicFocus());
                });
    }

    public List<Participant> findByDiscussionId(String discussionId) {
        return jdbcTemplate.query("SELECT discussion_id, id, role, display_name, profession, title, stance, "
                + "perspective, color_key, stage_status, public_focus FROM participant WHERE discussion_id = ? "
                + "ORDER BY CASE role WHEN 'HOST' THEN 0 ELSE 1 END, id ASC", MAPPER, discussionId);
    }

    /** 更新席位状态与受控公开关注点；IDLE 时关注点必须为空。 */
    public void updateStage(String discussionId, String id, String stageStatus, String publicFocus) {
        jdbcTemplate.update("UPDATE participant SET stage_status = ?, public_focus = ? "
                + "WHERE discussion_id = ? AND id = ?", stageStatus, publicFocus, discussionId, id);
    }

    public int countByDiscussion(String discussionId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM participant WHERE discussion_id = ?",
                Integer.class, discussionId);
        return count == null ? 0 : count;
    }

    public void resetAllStages(String discussionId) {
        jdbcTemplate.update("UPDATE participant SET stage_status = 'IDLE', public_focus = NULL WHERE discussion_id = ?",
                discussionId);
    }
}
