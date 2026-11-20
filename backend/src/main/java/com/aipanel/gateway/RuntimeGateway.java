package com.aipanel.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface RuntimeGateway {

    String generate(RuntimeTask task, Map<String, Object> input, List<String> validationCodes, Instant deadline);

    default int inputLimit(RuntimeTask task) {
        return 6000;
    }

    default int estimate(RuntimeTask task, Map<String, Object> input) {
        return 0;
    }
}
