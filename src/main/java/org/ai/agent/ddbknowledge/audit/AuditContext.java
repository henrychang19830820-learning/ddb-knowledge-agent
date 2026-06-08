package org.ai.agent.ddbknowledge.audit;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AuditContext {
    private String traceId;
    private String capturedPrompt;

    @Builder.Default
    private List<Map<String, Object>> toolExecutions = new ArrayList<>();
}
