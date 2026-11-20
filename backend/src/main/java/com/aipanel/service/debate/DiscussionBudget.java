package com.aipanel.service.debate;

import java.time.Clock;
import java.time.Instant;

public final class DiscussionBudget {

    private static final int MAX_REQUESTS = 112;
    private static final int SUMMARY_REQUESTS = 2;

    private final Clock clock;
    private final Instant end;
    private int requests;
    private int reserved;

    public DiscussionBudget(Clock clock) {
        this.clock = clock;
        this.end = clock.instant().plusSeconds(360);
    }

    public Instant deadline(boolean summary) {
        return summary ? end : end.minusSeconds(22);
    }

    public synchronized boolean openWindow(int expertCount) {
        if (!canRun(expertCount + 2)) {
            return false;
        }
        reserved = expertCount + 2;
        return true;
    }

    public synchronized boolean canRun(int needed) {
        return requests + reserved + needed <= MAX_REQUESTS - SUMMARY_REQUESTS
                && clock.instant().isBefore(deadline(false));
    }

    public synchronized void closeWindow() {
        reserved = 0;
    }

    public synchronized boolean acquire(boolean summary, boolean firstWindowAttempt) {
        if (!clock.instant().isBefore(deadline(summary))) {
            return false;
        }
        if (firstWindowAttempt && reserved > 0) {
            reserved--;
            requests++;
            return true;
        }
        int maximum = summary ? MAX_REQUESTS : MAX_REQUESTS - SUMMARY_REQUESTS - reserved;
        if (requests >= maximum) {
            return false;
        }
        requests++;
        return true;
    }

    public synchronized int requests() {
        return requests;
    }
}
