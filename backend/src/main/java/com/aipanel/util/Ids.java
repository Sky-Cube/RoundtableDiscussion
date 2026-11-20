package com.aipanel.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 标识与时间文本。 */
public final class Ids {

    private Ids() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
