package com.aipanel.util;

import java.util.List;

/** 房间内色标分配：主持固定 violet，专家按顺序取互异色标。 */
public final class Colors {

    public static final String HOST = "violet";
    private static final List<String> EXPERT_PALETTE =
            List.of("blue", "green", "amber", "rose", "teal", "cyan");

    private Colors() {
    }

    public static String expertAt(int index) {
        if (index < 0 || index >= EXPERT_PALETTE.size()) {
            throw new IllegalArgumentException("专家色标越界：" + index);
        }
        return EXPERT_PALETTE.get(index);
    }
}
