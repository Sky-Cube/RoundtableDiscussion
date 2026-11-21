package com.aipanel.it;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.config.DatabaseInitializer;
import com.aipanel.config.GuardedSqliteDataSource;
import com.aipanel.config.PanelProperties;
import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.TestDirectories;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** P05-PRESET-VALID：初始化幂等，预设复制生成两个互不影响的独立草稿且不调用模型。 */
class P05PresetValidIT extends AbstractPanelIntegrationTest {

    @Test
    @DisplayName("空库连续初始化两次，预设数量与内容不变")
    void initializesIdempotentlyOnEmptyDatabase() throws IOException {
        Path directory = TestDirectories.create("p05-fresh-" + UUID.randomUUID());
        try {
            PanelProperties properties = new PanelProperties(
                    new PanelProperties.Database(directory.resolve("fresh.db").toString(), directory.toString(),
                            "classpath:db/init.sql", true),
                    null, null, null);
            GuardedSqliteDataSource dataSource = new GuardedSqliteDataSource(
                    directory.resolve("fresh.db"), directory);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            DatabaseInitializer initializer = new DatabaseInitializer(dataSource, jdbc, properties);

            initializer.initialize();
            int firstCount = initializer.countPresets();
            String firstFingerprint = fingerprint(jdbc);

            jdbc.update("""
                    INSERT INTO preset VALUES ('ai-support-buy-build', '首版内置议题', 'EVALUATE', 4)
                    """);
            jdbc.update("""
                    INSERT INTO preset_participant
                    SELECT 'ai-support-buy-build', id, role, display_name, profession, title, stance, perspective, color_key
                    FROM preset_participant WHERE preset_slug = 'ai-graduate-careers'
                    """);
            initializer.initialize();

            assertThat(firstCount).isEqualTo(6);
            assertThat(initializer.countPresets()).isEqualTo(6);
            assertThat(fingerprint(jdbc)).isEqualTo(firstFingerprint);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM preset_participant", Integer.class)).isEqualTo(30);
            assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        } finally {
            TestDirectories.deleteRecursively(directory);
        }
    }

    @Test
    @DisplayName("六个预设按 slug 升序返回，主持排在阵容首位")
    void listsPresetsInSlugOrder() throws Exception {
        JsonNode presets = getData("/api/v1/presets");

        assertThat(presets).hasSize(6);
        assertThat(presets.get(0).get("slug").asText()).isEqualTo("ai-graduate-careers");
        assertThat(presets.get(5).get("slug").asText()).isEqualTo("rental-repair-disputes");
        for (JsonNode preset : presets) {
            assertThat(preset.get("participants").get(0).get("role").asText()).isEqualTo("HOST");
            assertThat(preset.get("expertCount").asInt()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("同一预设连续复制两次得到独立草稿，内容一致且不调用模型")
    void copiesPresetTwiceWithoutModel() throws Exception {
        String slug = "bus-demand-shuttle";
        JsonNode presets = getData("/api/v1/presets");
        JsonNode preset = null;
        for (JsonNode candidate : presets) {
            if (slug.equals(candidate.get("slug").asText())) {
                preset = candidate;
            }
        }
        assertThat(preset).isNotNull();
        int discussions = count("SELECT COUNT(*) FROM discussion");

        MvcResult first = copyPreset(slug);
        MvcResult second = copyPreset(slug);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstRoom = dataOf(first);
        JsonNode secondRoom = dataOf(second);
        assertThat(firstRoom.get("id").asText()).isNotEqualTo(secondRoom.get("id").asText());
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions + 2);

        for (JsonNode room : List.of(firstRoom, secondRoom)) {
            assertThat(room.get("status").asText()).isEqualTo("DRAFT");
            assertThat(room.get("lastEventSeq").asInt()).isZero();
            assertThat(room.get("topic").asText()).isEqualTo(preset.get("topic").asText());
            assertThat(room.get("discussionGoal").asText()).isEqualTo(preset.get("discussionGoal").asText());
            assertThat(room.get("expertCount").asInt()).isEqualTo(preset.get("expertCount").asInt());
            assertThat(room.get("participants")).hasSize(5);
            assertThat(room.get("participants").get(0).get("id").asText()).isEqualTo("copy-host");
            String roomId = room.get("id").asText();
            assertThat(count("SELECT COUNT(*) FROM discussion_event WHERE discussion_id = ?", roomId)).isZero();
            for (int index = 1; index < room.get("participants").size(); index++) {
                JsonNode member = room.get("participants").get(index);
                JsonNode expected = preset.get("participants").get(index);
                assertThat(member.get("id").asText()).isEqualTo("copy-e" + index);
                assertThat(member.get("stance").asText()).isEqualTo(expected.get("stance").asText());
                assertThat(member.get("perspective").asText()).isEqualTo(expected.get("perspective").asText());
                assertThat(member.get("colorKey").asText()).isEqualTo(expected.get("colorKey").asText());
            }
        }
        assertThat(gateway.totalCalls()).isZero();
    }

    private String fingerprint(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT group_concat(slug || '|' || topic || '|' || discussion_goal || '|' "
                + "|| expert_count, ';') FROM (SELECT * FROM preset ORDER BY slug)", String.class)
                + "::"
                + jdbc.queryForObject("SELECT group_concat(preset_slug || '|' || id || '|' || role || '|' "
                + "|| display_name || '|' || coalesce(stance, '') || '|' || coalesce(perspective, '') || '|' "
                + "|| color_key, ';') FROM (SELECT * FROM preset_participant ORDER BY preset_slug, id)",
                        String.class);
    }
}
