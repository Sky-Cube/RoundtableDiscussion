package com.aipanel.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aipanel.model.Insight;

/** 结论与证据行仓储。一次成功提炼全量替换本场投影。 */
@Repository
public class InsightRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsightRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Insight> findByDiscussionId(String discussionId) {
        Map<String, List<Integer>> evidence = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT insight_id, utterance_seq FROM insight_evidence WHERE discussion_id = ? "
                + "ORDER BY insight_id ASC, utterance_seq ASC", rows -> {
                    evidence.computeIfAbsent(rows.getString("insight_id"), key -> new ArrayList<>())
                            .add(rows.getInt("utterance_seq"));
                }, discussionId);

        return jdbcTemplate.query("SELECT id, kind, text FROM insight WHERE discussion_id = ? ORDER BY id ASC",
                (rows, index) -> new Insight(rows.getString("id"), rows.getString("kind"), rows.getString("text"),
                        List.copyOf(evidence.getOrDefault(rows.getString("id"), List.of()))),
                discussionId);
    }

    /** 全量替换：先删旧结论与证据，再写入新投影。调用方需在同一事务内完成。 */
    public void replaceAll(String discussionId, List<Insight> insights, String updatedAt) {
        jdbcTemplate.update("DELETE FROM insight WHERE discussion_id = ?", discussionId);
        for (Insight insight : insights) {
            jdbcTemplate.update("INSERT INTO insight (discussion_id, id, kind, text, updated_at) VALUES (?, ?, ?, ?, ?)",
                    discussionId, insight.id(), insight.kind(), insight.text(), updatedAt);
            for (Integer seq : insight.evidenceUtteranceSeqs()) {
                jdbcTemplate.update("INSERT INTO insight_evidence (discussion_id, insight_id, utterance_seq) "
                        + "VALUES (?, ?, ?)", discussionId, insight.id(), seq);
            }
        }
    }

    public int countByDiscussion(String discussionId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM insight WHERE discussion_id = ?",
                Integer.class, discussionId);
        return count == null ? 0 : count;
    }
}
