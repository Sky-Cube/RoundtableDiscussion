package com.aipanel.gateway;

public enum RuntimeTask {
    R01("rosterOutput", 2304), R02("bidOutput", 1024), R03("utteranceOutput", 640),
    R04("insightsOutput", 1500), R05("utteranceOutput", 640),
    R06("interventionOutput", 640), R07("summaryOutput", 768), EVIDENCE_CHECK("skillEvidenceOutput", 1000);

    private final String schema;
    private final int outputTokens;

    RuntimeTask(String schema, int outputTokens) {
        this.schema = schema;
        this.outputTokens = outputTokens;
    }

    public String schema() {
        return schema;
    }

    public int outputTokens() {
        return outputTokens;
    }
}
