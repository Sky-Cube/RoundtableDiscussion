package com.aipanel.gateway;

import java.util.List;

/** R01 阵容输出的强类型视图。id、colorKey、状态与游标由后端生成，模型不得输出。 */
public record RosterOutput(String discussionGoal, List<RosterParticipant> participants) {
}
