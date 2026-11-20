package com.aipanel.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aipanel.gateway.RosterParser;
import com.aipanel.gateway.ValidationCodes;
import com.aipanel.service.RosterQualityService;
import com.aipanel.support.RosterFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Q01 在线确定性部分：只判定完全相同或占位的立场。 */
class RosterQualityServiceTest {

    private final RosterParser parser = new RosterParser(new ObjectMapper());
    private final RosterQualityService quality = new RosterQualityService();

    @Test
    @DisplayName("立场互异时通过")
    void acceptsDistinctStances() {
        assertThat(quality.validate(parser.parse(RosterFixtures.valid("EVALUATE", 4)))).isEmpty();
    }

    @Test
    @DisplayName("规范化后完全相同的立场判为 LOW_QUALITY")
    void rejectsIdenticalStances() {
        String json = RosterFixtures.duplicateStance("先小范围试点，指标达标后再扩大推广", 4);

        assertThat(quality.validate(parser.parse(json))).containsExactly(ValidationCodes.LOW_QUALITY);
    }

    @Test
    void rejectsRoleWithoutAnyProfessionalResponsibility() {
        var roster = RosterFixtures.base("EXPLORE", 2);
        RosterFixtures.expert(roster, 1).put("profession", "行业专家").put("title", "资深顾问");
        assertThat(quality.validate(parser.parse(RosterFixtures.json(roster)))).containsExactly(ValidationCodes.LOW_QUALITY);
        RosterFixtures.expert(roster, 1).put("profession", "软件研发校园招聘");
        assertThat(quality.validate(parser.parse(RosterFixtures.json(roster)))).isEmpty();
    }

    @Test
    @DisplayName("只含占位语的立场判为 LOW_QUALITY")
    void rejectsPlaceholderStance() {
        String json = RosterFixtures.duplicateStance("具体问题具体分析", 4);

        assertThat(quality.validate(parser.parse(json))).containsExactly(ValidationCodes.LOW_QUALITY);
    }
}