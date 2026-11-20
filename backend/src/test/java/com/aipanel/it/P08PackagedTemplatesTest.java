package com.aipanel.it;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class P08PackagedTemplatesTest {
    @Test
    void packagesCanonicalTemplatesAndContract() {
        assertThat(getClass().getResource("/prompts/roles/expert-bid.md")).isNotNull();
        assertThat(getClass().getResource("/skills/insight-evidence-retrieval/SKILL.md")).isNotNull();
        assertThat(getClass().getResource("/prompts/workflow.json")).isNotNull();
        assertThat(getClass().getResource("/docs/runtime-contract.schema.json")).isNotNull();
    }
}
