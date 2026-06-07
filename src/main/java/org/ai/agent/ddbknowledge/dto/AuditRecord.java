package org.ai.agent.ddbknowledge.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditRecord {
    private String queryText;
    private String modelName;
    private int inputTokens;
    private int outputTokens;
    private long ttftMs;
    private long totalLatencyMs;
    private boolean isCacheHit;
    private String traceId;
    private Integer complexityScore;
}
