package com.aipanel.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;

import static org.assertj.core.api.Assertions.assertThat;

/** P05-SEMANTICS：立场完全相同的低质量阵容被拒绝，且只补偿一次。 */
class P05SemanticsIT extends AbstractPanelIntegrationTest {

    private static final String TOPIC = "采购还是自研 AI 客服";
    private static final String REPAIRED_TOPIC = "采购还是自研 AI 客服（补偿后合格）";
    private static final String SAME_STANCE = "先小范围试点，指标达标后再扩大推广";

    @Test
    @DisplayName("两位专家立场完全相同，补偿后仍低质量则 502，上游调用 2 次")
    void rejectsLowQualityRoster() throws Exception {
        String lowQuality = RosterFixtures.duplicateStance(SAME_STANCE, 2);
        gateway.script(TOPIC, StubModelGateway.Step.json(lowQuality), StubModelGateway.Step.json(lowQuality));
        int discussions = count("SELECT COUNT(*) FROM discussion");
        int participants = count("SELECT COUNT(*) FROM participant");

        MvcResult result = createDraft(createBody(TOPIC, 2));

        assertThat(gateway.calls(TOPIC)).isEqualTo(2);
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(errorOf(result).get("code").intValue()).isEqualTo(502);
        assertThat(errorOf(result).get("data").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM discussion")).isEqualTo(discussions);
        assertThat(count("SELECT COUNT(*) FROM participant")).isEqualTo(participants);
        assertThat(count("SELECT COUNT(*) FROM discussion WHERE topic = ?", TOPIC)).isZero();
    }

    @Test
    @DisplayName("补偿后立场出现实质差异则创建成功")
    void acceptsRepairedRoster() throws Exception {
        String lowQuality = RosterFixtures.duplicateStance(SAME_STANCE, 2);
        gateway.script(REPAIRED_TOPIC, StubModelGateway.Step.json(lowQuality),
                StubModelGateway.Step.json(RosterFixtures.valid("EVALUATE", 2)));

        MvcResult result = createDraft(createBody(REPAIRED_TOPIC, 2));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(gateway.calls(REPAIRED_TOPIC)).isEqualTo(2);
        assertThat(dataOf(result).get("status").asText()).isEqualTo("DRAFT");
    }
}