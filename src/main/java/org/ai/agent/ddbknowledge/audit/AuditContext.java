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

    public void addToolExecution(String name, String query, String result) {
        Map<String, Object> exec = new java.util.HashMap<>();
        exec.put("name", name);
        exec.put("query", query);
        exec.put("result", result);
        exec.put("timestamp", java.time.LocalDateTime.now().toString());
        toolExecutions.add(exec);
    }
}

