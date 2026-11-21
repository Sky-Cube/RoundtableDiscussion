package com.aipanel.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aipanel.AiPanelApplication;
import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.aipanel.support.TestDirectories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiscussionCleanupIT extends AbstractPanelIntegrationTest {

    @Test
    void deletesOnlyInterruptedRoomAndItsRelatedRecords() throws Exception {
        String interrupted = draft("待清理的中断讨论");
        String other = draft("仍然运行的另一场");
        activeWithTranscript(interrupted, "RUNNING", jdbcTemplate);
        activeWithTranscript(other, "SUMMARIZING", jdbcTemplate);
        jdbcTemplate.update("""
                UPDATE discussion SET status = 'FAILED', ended_at = started_at, error_code = 'B0005' WHERE id = ?
                """, interrupted);
        mockMvc.perform(delete("/api/v1/discussions/" + other)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/discussions/" + interrupted)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/discussions/" + interrupted)).andExpect(status().isNotFound());
        for (String table : new String[]{"participant", "utterance", "insight", "insight_evidence", "discussion_event"}) {
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE discussion_id = ?", interrupted)).isZero();
            assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE discussion_id = ?", other)).isPositive();
        }
        assertThat(getData("/api/v1/discussions/" + other).path("status").asText()).isEqualTo("SUMMARIZING");
        jdbcTemplate.update("UPDATE discussion SET status = 'FAILED', ended_at = started_at, error_code = 'B0005' WHERE id = ?", other);
    }

    @Test
    void recoversBothUnfinishedStatesAtStartupAndKeepsTheirTranscript() {
        var directory = TestDirectories.create("interrupted");
        String[] options = {"--panel.db.path=" + directory.resolve("panel.db"), "--panel.db.allowed-root=" + directory};
        try {
            try (var context = startContext(options)) {
                JdbcTemplate db = context.getBean(JdbcTemplate.class);
                for (String state : new String[]{"RUNNING", "SUMMARIZING"}) {
                    db.update("""
                            INSERT INTO discussion (id, topic, discussion_goal, expert_count, created_at)
                            VALUES (?, '留存的测试讨论', 'EXPLORE', 2, '2026-11-21T00:00:00Z')
                            """, state);
                    db.update("""
                            INSERT INTO participant (discussion_id, id, role, display_name, profession, title, color_key)
                            VALUES (?, 'host', 'HOST', '测试主持', '节目主持', '圆桌主持', 'violet')
                            """, state);
                    activeWithTranscript(state, state, db);
                }
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var context = startContext(options)) {
                    JdbcTemplate db = context.getBean(JdbcTemplate.class);
                    assertThat(db.queryForList("SELECT status FROM discussion", String.class))
                            .containsExactlyInAnyOrder("FAILED", "FAILED");
                    assertThat(db.queryForList("SELECT stage_status FROM participant", String.class))
                            .containsOnly("IDLE");
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM utterance", Integer.class)).isEqualTo(4);
                    assertThat(db.queryForObject("""
                            SELECT COUNT(*) FROM discussion_event
                            WHERE type = 'discussion.status' AND json_extract(payload_json, '$.payload.status') = 'FAILED'
                            """, Integer.class)).isEqualTo(2);
                }
            }
        } finally {
            TestDirectories.deleteRecursively(directory);
        }
    }

    private static org.springframework.context.ConfigurableApplicationContext startContext(String[] options) {
        return new SpringApplicationBuilder(AiPanelApplication.class).profiles("test")
                .web(WebApplicationType.NONE).run(options);
    }

    private String draft(String topic) throws Exception {
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("EXPLORE", 2)));
        return dataOf(createDraft(createBody(topic, 2))).path("id").asText();
    }

    private static void activeWithTranscript(String id, String status, JdbcTemplate db) {
        String time = "2026-11-21T00:00:00Z";
        db.update("UPDATE discussion SET status = ?, started_at = ?, last_event_seq = 1 WHERE id = ?", status, time, id);
        db.update("UPDATE participant SET stage_status = 'SPEAKING' WHERE discussion_id = ? AND id = 'host'", id);
        db.update("""
                INSERT INTO utterance (discussion_id, seq, participant_id, kind, content, reply_to_utterance_seq, created_at)
                VALUES (?, 1, 'host', 'OPENING', '第一条实录。', NULL, ?),
                       (?, 2, 'host', 'FOLLOW_UP', '第二条实录。', 1, ?)
                """, id, time, id, time);
        db.update("INSERT INTO insight VALUES (?, 'test', 'CONSENSUS', '合成结论内容', ?)", id, time);
        db.update("INSERT INTO insight_evidence VALUES (?, 'test', 2)", id);
        db.update("UPDATE discussion SET insight_status = 'FRESH', insights_through_utterance_seq = 2 WHERE id = ?", id);
        db.update("INSERT INTO discussion_event VALUES (?, 1, 'discussion.status', '{}', ?)", id, time);
    }
}
