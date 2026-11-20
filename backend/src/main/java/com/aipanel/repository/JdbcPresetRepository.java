package com.aipanel.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aipanel.model.Preset;
import com.aipanel.model.PresetParticipant;

/** 预设仓储的 SQLite 实现。 */
@Repository
public class JdbcPresetRepository implements PresetRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPresetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Preset> findAll() {
        List<Preset> presets = jdbcTemplate.query(
                "SELECT slug, topic, discussion_goal, expert_count FROM preset ORDER BY slug ASC",
                (rows, index) -> new Preset(rows.getString("slug"), rows.getString("topic"),
                        rows.getString("discussion_goal"), rows.getInt("expert_count"), new ArrayList<>()));
        List<Preset> result = new ArrayList<>(presets.size());
        for (Preset preset : presets) {
            result.add(new Preset(preset.slug(), preset.topic(), preset.discussionGoal(), preset.expertCount(),
                    participants(preset.slug())));
        }
        return result;
    }

    @Override
    public Optional<Preset> findBySlug(String slug) {
        List<Preset> rows = jdbcTemplate.query(
                "SELECT slug, topic, discussion_goal, expert_count FROM preset WHERE slug = ?",
                (resultSet, index) -> new Preset(resultSet.getString("slug"), resultSet.getString("topic"),
                        resultSet.getString("discussion_goal"), resultSet.getInt("expert_count"), List.of()),
                slug);
        return rows.stream().findFirst()
                .map(preset -> new Preset(preset.slug(), preset.topic(), preset.discussionGoal(),
                        preset.expertCount(), participants(preset.slug())));
    }

    private List<PresetParticipant> participants(String slug) {
        return jdbcTemplate.query("SELECT id, role, display_name, profession, title, stance, perspective, color_key "
                + "FROM preset_participant WHERE preset_slug = ? "
                + "ORDER BY CASE role WHEN 'HOST' THEN 0 ELSE 1 END, id ASC",
                (rows, index) -> new PresetParticipant(rows.getString("id"), rows.getString("role"),
                        rows.getString("display_name"), rows.getString("profession"), rows.getString("title"),
                        rows.getString("stance"), rows.getString("perspective"), rows.getString("color_key")),
                slug);
    }
}
