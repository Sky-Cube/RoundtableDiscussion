package com.aipanel.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aipanel.gateway.RosterOutput;
import com.aipanel.gateway.RosterParser;
import com.aipanel.gateway.RosterStructureException;
import com.aipanel.gateway.ValidationCodes;
import com.aipanel.service.RosterValidator;
import com.aipanel.support.RosterFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 阵容结构校验的确定性断言。 */
class RosterValidatorTest {

    private final RosterParser parser = new RosterParser(new ObjectMapper());
    private final RosterValidator validator = new RosterValidator();

    @Test
    @DisplayName("合法阵容通过校验")
    void acceptsValidRoster() {
        assertThat(validate(RosterFixtures.base("EVALUATE", 4), 4)).isEmpty();
    }

    @Test
    @DisplayName("主持不在首位、缺少主持、多个主持都被拒绝")
    void rejectsHostPlacement() {
        ObjectNode notFirst = RosterFixtures.base("EVALUATE", 3);
        ObjectNode host = RosterFixtures.host(notFirst);
        notFirst.withArray("participants").remove(0);
        notFirst.withArray("participants").add(host);
        assertThat(validate(notFirst, 3)).contains(ValidationCodes.HOST_NOT_FIRST);

        ObjectNode missing = RosterFixtures.base("EVALUATE", 3);
        missing.withArray("participants").remove(0);
        assertThat(validate(missing, 3)).contains(ValidationCodes.HOST_COUNT);

        ObjectNode multiple = RosterFixtures.base("EVALUATE", 3);
        RosterFixtures.expert(multiple, 1).put("role", "HOST").put("stance", (String) null).put("perspective", (String) null);
        assertThat(validate(multiple, 3)).contains(ValidationCodes.HOST_COUNT);
    }

    @Test
    @DisplayName("主持携带立场或视角、专家视角重复或越界都被拒绝")
    void rejectsPerspectiveRules() {
        ObjectNode hostWithPerspective = RosterFixtures.base("EVALUATE", 3);
        RosterFixtures.host(hostWithPerspective).put("perspective", "COST");
        assertThat(validate(hostWithPerspective, 3)).contains(ValidationCodes.HOST_FIELD_INVALID);

        ObjectNode duplicate = RosterFixtures.base("EVALUATE", 3);
        RosterFixtures.expert(duplicate, 2).put("perspective", RosterFixtures.expert(duplicate, 1).get("perspective").asText());
        assertThat(validate(duplicate, 3)).contains(ValidationCodes.PERSPECTIVE_DUPLICATE);

        ObjectNode invalid = RosterFixtures.base("EVALUATE", 3);
        RosterFixtures.expert(invalid, 1).put("perspective", "MAGIC");
        assertThat(validate(invalid, 3)).contains(ValidationCodes.PERSPECTIVE_INVALID);
    }

    @Test
    @DisplayName("规范化后重名、字段长度越界与过短立场都被拒绝")
    void rejectsNamesAndLengths() {
        ObjectNode duplicateName = RosterFixtures.base("EVALUATE", 3);
        RosterFixtures.expert(duplicateName, 2).put("displayName", RosterFixtures.expert(duplicateName, 1).get("displayName").asText() + " ");
        assertThat(validate(duplicateName, 3)).contains(ValidationCodes.NAME_DUPLICATE);

        ObjectNode shortStance = RosterFixtures.base("EVALUATE", 3);
        RosterFixtures.expert(shortStance, 1).put("stance", "太短");
        assertThat(validate(shortStance, 3)).contains(ValidationCodes.STANCE_INVALID);

        ObjectNode blankTitle = RosterFixtures.base("EVALUATE", 3);
        RosterFixtures.expert(blankTitle, 1).put("title", "");
        assertThat(validate(blankTitle, 3)).contains(ValidationCodes.FIELD_LENGTH_INVALID);
    }

    @Test
    @DisplayName("讨论目标缺失或为未知枚举时被拒绝，数组目标在解析阶段即失败")
    void rejectsGoal() {
        ObjectNode missing = RosterFixtures.base("EVALUATE", 3);
        missing.remove("discussionGoal");
        assertThat(validate(missing, 3)).contains(ValidationCodes.GOAL_MISSING);

        ObjectNode unknown = RosterFixtures.base("EVALUATE", 3);
        unknown.put("discussionGoal", "VAGUE");
        assertThat(validate(unknown, 3)).contains(ValidationCodes.GOAL_INVALID);

        ObjectNode goalArray = RosterFixtures.base("EVALUATE", 3);
        goalArray.putArray("discussionGoal").add("EVALUATE");
        assertThatThrownBy(() -> parser.parse(RosterFixtures.json(goalArray)))
                .isInstanceOf(RosterStructureException.class);
    }

    private java.util.List<String> validate(ObjectNode root, int expertCount) {
        RosterOutput output = parser.parse(RosterFixtures.json(root));
        return validator.validate(output, expertCount);
    }
}