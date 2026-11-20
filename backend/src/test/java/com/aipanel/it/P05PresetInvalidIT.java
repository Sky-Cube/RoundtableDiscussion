package com.aipanel.it;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.model.Preset;
import com.aipanel.model.PresetParticipant;
import com.aipanel.repository.PresetRepository;
import com.aipanel.support.AbstractPanelIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** P05-PRESET-INVALID：slug 非法、预设不存在与损坏预设三类负用例。 */
class P05PresetInvalidIT extends AbstractPanelIntegrationTest {

    @MockitoBean
    private PresetRepository presetRepository;

    @Test
    @DisplayName("slug 格式非法返回 400")
    void rejectsInvalidSlugFormat() throws Exception {
        int discussions = count("SELECT COUNT(*) FROM discussion");

        MvcResult result = copyPreset("Bad_Preset");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(400);
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
    }

    @Test
    @DisplayName("slug 合法但不存在返回 404")
    void reportsMissingPreset() throws Exception {
        when(presetRepository.findBySlug("not-found-preset")).thenReturn(Optional.empty());
        int discussions = count("SELECT COUNT(*) FROM discussion");

        MvcResult result = copyPreset("not-found-preset");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(404);
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingHost", "invalidRole", "duplicatePerspective"})
    @DisplayName("损坏预设在读取与复制时都返回 500，且不写任何记录")
    void rejectsCorruptedPreset(String variant) throws Exception {
        Preset corrupted = corrupted(variant);
        when(presetRepository.findAll()).thenReturn(List.of(corrupted));
        when(presetRepository.findBySlug(corrupted.slug())).thenReturn(Optional.of(corrupted));
        int discussions = count("SELECT COUNT(*) FROM discussion");
        int participants = count("SELECT COUNT(*) FROM participant");
        int events = count("SELECT COUNT(*) FROM discussion_event");

        MvcResult list = mockMvc.perform(get("/api/v1/presets")).andReturn();
        MvcResult copy = copyPreset(corrupted.slug());

        assertThat(list.getResponse().getStatus()).isEqualTo(500);
        assertThat(errorOf(list).get("code").intValue()).isEqualTo(500);
        assertThat(copy.getResponse().getStatus()).isEqualTo(500);
        assertThat(errorOf(copy).get("code").intValue()).isEqualTo(500);
        assertThat(errorOf(copy).get("data").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
        assertThat(count("SELECT COUNT(*) FROM participant")).isEqualTo(participants);
        assertThat(count("SELECT COUNT(*) FROM discussion_event")).isEqualTo(events);
    }

    private Preset corrupted(String variant) {
        PresetParticipant host = new PresetParticipant("host", "HOST", "顾言", "节目主持", "圆桌主持人", null, null,
                "violet");
        PresetParticipant cost = new PresetParticipant("e1", "EXPERT", "陈简", "成本管理", "成本控制负责人",
                "四周内交付时优先采购成熟方案", "COST", "blue");
        PresetParticipant delivery = new PresetParticipant("e2", "EXPERT", "林昭", "软件工程", "平台架构师",
                "规则频繁变更时维护自研编排", "DELIVERY", "green");
        return switch (variant) {
            case "missingHost" -> new Preset("broken-preset", "损坏预设用例", "EVALUATE", 2,
                    List.of(cost, delivery));
            case "invalidRole" -> new Preset("broken-preset", "损坏预设用例", "EVALUATE", 2,
                    List.of(host, cost, new PresetParticipant("e3", "MODERATOR", "越界", "角色", "非法角色",
                            "角色不合法时也应当被拒绝", "RISK", "rose")));
            case "duplicatePerspective" -> new Preset("broken-preset", "损坏预设用例", "EVALUATE", 2,
                    List.of(host, cost, new PresetParticipant("e3", "EXPERT", "赵岚", "客服运营", "客户运营经理",
                            "小流量核验服务效果再逐步拓展", "COST", "amber")));
            default -> throw new IllegalArgumentException(variant);
        };
    }
}