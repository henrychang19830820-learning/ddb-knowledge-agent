package org.ai.agent.ddbknowledge.audit;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditContext {
    private String traceId;
    private String capturedPrompt;
}
