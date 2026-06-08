# Integrated Template & Tool Auditing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a mandatory source-aware response template and capture detailed JSON payloads for every tool call in the audit logs.

**Architecture:** Add `tool_calls` column to PostgreSQL. Update `AuditContext` to store a list of tool executions. Enhance `ChatModelAuditListener` to capture tool requests and results. Update `QueryRoutingService` to use the new template and pass tool data to the audit log.

**Tech Stack:** Java 21, Spring Boot, LangChain4j, PostgreSQL.

---

### Task 1: Database Schema & DTO Update (tool_calls)

**Files:**
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java`

- [ ] **Step 1: Update schema.sql**

Add `tool_calls JSONB` to `request_audit_logs`.

```sql
-- In src/main/resources/schema.sql
CREATE TABLE request_audit_logs (
    ...
    tool_calls JSONB,
    ...
);
```

- [ ] **Step 2: Apply schema changes manually**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "ALTER TABLE request_audit_logs ADD COLUMN tool_calls JSONB;"`

- [ ] **Step 3: Update AuditContext DTO**

Add storage for a list of tool executions.

```java
// In src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java
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
```

- [ ] **Step 4: Update AuditRecord DTO**

```java
// In src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java
    private String toolCallsJson;
```

- [ ] **Step 5: Update AuditService**

Update the SQL insert to handle the new column.

```java
// In src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java
        String sql = """
            INSERT INTO request_audit_logs 
            (query_text, full_prompt, model_name, input_tokens, output_tokens, total_tokens, 
             input_cost, output_cost, total_cost, ttft_ms, total_latency_ms, is_cache_hit, 
             trace_id, complexity_score, tool_calls)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

        jdbcTemplate.update(sql,
                record.getQueryText(),
                record.getFullPrompt(),
                record.getModelName(),
                // ... other fields ...
                record.getTraceId() != null ? java.util.UUID.fromString(record.getTraceId()) : null,
                record.getComplexityScore(),
                record.getToolCallsJson()
        );
```

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "feat: add tool_calls column to audit schema and DTOs"
```

---

### Task 2: Enhance ChatModelAuditListener

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/audit/ChatModelAuditListener.java`

- [ ] **Step 1: Capture Tool Requests in onResponse**

Intercept when the LLM asks to run a tool.

```java
    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        if (responseContext.response() != null && responseContext.response().content() != null) {
            List<dev.langchain4j.agent.tool.ToolExecutionRequest> toolRequests = responseContext.response().content().toolExecutionRequests();
            if (toolRequests != null && !toolRequests.isEmpty()) {
                AuditContext context = AuditContextHolder.get();
                if (context != null) {
                    for (dev.langchain4j.agent.tool.ToolExecutionRequest req : toolRequests) {
                        java.util.Map<String, Object> exec = new java.util.HashMap<>();
                        exec.put("id", req.id());
                        exec.put("name", req.name());
                        exec.put("arguments", req.arguments());
                        context.getToolExecutions().add(exec);
                    }
                }
            }
        }
    }
```

- [ ] **Step 2: Capture Tool Results in onRequest**

Intercept when the Tool result is being sent BACK to the LLM in the next turn.

```java
    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // ... existing prompt capture code ...

        // Also check for tool results in the messages
        for (dev.langchain4j.data.message.ChatMessage msg : requestContext.request().messages()) {
            if (msg instanceof dev.langchain4j.data.message.ToolExecutionResultMessage toolMsg) {
                AuditContext context = AuditContextHolder.get();
                if (context != null) {
                    // Match result to the request already in context
                    context.getToolExecutions().stream()
                            .filter(e -> toolMsg.id().equals(e.get("id")))
                            .findFirst()
                            .ifPresent(e -> e.put("result", toolMsg.text()));
                }
            }
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/audit/ChatModelAuditListener.java
git commit -m "feat: capture detailed tool execution requests and results in listener"
```

---

### Task 3: Implement Template & Final Integration

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Update systemPrompt with Template**

```java
// In QueryRoutingService.java
        String systemPrompt = """
                You are a DynamoDB expert. You have access to a tool to search the official documentation. You should use it to look up specific technical details.

                You MUST format your entire response using the following mandatory Markdown template:

                ### 📚 From Official Documentation
                [Provide information found ONLY using the search tool here. If the tool returned no results, state "No specific documentation found for this query."]

                ### 🧠 From Expert Knowledge
                [Provide supplementary information from your own training data here to give a more complete or practical answer.]

                ---
                **Constraint:** Do not hallucinate technical specifications. If there is a conflict, always prioritize information from the documentation tool.
                """;
```

- [ ] **Step 2: Serialize and Pass Tool Calls to Audit**

Use `ObjectMapper` to convert the `List<Map>` to JSON string before calling `recordAudit`.

```java
// In QueryRoutingService.java callbacks
    String toolCallsJson = null;
    try {
        toolCallsJson = new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(auditContext.getToolExecutions());
    } catch (Exception e) { /* ignore */ }

    auditService.recordAudit(AuditRecord.builder()
            ...
            .toolCallsJson(toolCallsJson)
            .build());
```

- [ ] **Step 3: Verify and Commit**

Run: `./gradlew classes`

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: implement source-aware template and integrate tool auditing"
```

---

### Task 4: Final E2E Verification

- [ ] **Step 1: Start Application**

Run: `fuser -k 8090/tcp || true; ./gradlew bootRun`

- [ ] **Step 2: Verify Template in UI**

Ask: "What is the partition key?"
Check for the 📚 and 🧠 headers.

- [ ] **Step 3: Verify Tool Logs in DB**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "SELECT tool_calls FROM request_audit_logs WHERE tool_calls IS NOT NULL ORDER BY created_at DESC LIMIT 1;"`
Expected: A valid JSON array containing the `searchDocumentation` call, its arguments, and the text chunks returned.

- [ ] **Step 4: Cleanup**

```bash
git commit -am "chore: finalize template and tool auditing"
```
