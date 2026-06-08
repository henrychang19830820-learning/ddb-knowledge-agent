# Database Schema & DTO Update (tool_calls) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `tool_calls` JSONB column to the audit log table and update Java DTOs to support it.

**Architecture:** Extend the existing audit logging mechanism to capture tool execution details. This involves a database schema change, updating the persistent `AuditRecord` DTO, and modifying the `AuditService` to handle the new column.

**Tech Stack:** PostgreSQL (JSONB), Spring JDBC, Java 17+, Lombok.

---

### Task 1: Update PostgreSQL Schema

**Files:**
- Modify: `src/main/resources/schema.sql`

- [ ] **Step 1: Add tool_calls column to schema.sql**

```sql
<<<<
    -- Metadata
    is_cache_hit BOOLEAN DEFAULT FALSE,
    complexity_score INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
====
    -- Metadata
    is_cache_hit BOOLEAN DEFAULT FALSE,
    complexity_score INTEGER,
    tool_calls JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
>>>>
```

- [ ] **Step 2: Apply change to live database**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "ALTER TABLE request_audit_logs ADD COLUMN tool_calls JSONB;"`
Expected: `ALTER TABLE`

### Task 2: Update AuditContext

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java`

- [ ] **Step 1: Add toolExecutions list**

```java
<<<<
@Data
@Builder
public class AuditContext {
    private String traceId;
    private String capturedPrompt;
}
====
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
>>>>
```

### Task 3: Update AuditRecord

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java`

- [ ] **Step 1: Add toolCallsJson field**

```java
<<<<
    private boolean isCacheHit;
    private String traceId;
    private Integer complexityScore;
}
====
    private boolean isCacheHit;
    private String traceId;
    private Integer complexityScore;
    private String toolCallsJson;
}
>>>>
```

### Task 4: Update AuditService

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java`

- [ ] **Step 1: Update INSERT SQL and parameters**

```java
<<<<
        String sql = """
            INSERT INTO request_audit_logs 
            (query_text, full_prompt, model_name, input_tokens, output_tokens, total_tokens, 
             input_cost, output_cost, total_cost, ttft_ms, total_latency_ms, is_cache_hit, trace_id, complexity_score)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                record.getQueryText(),
                record.getFullPrompt(),
                record.getModelName(),
                record.getInputTokens(),
                record.getOutputTokens(),
                record.getInputTokens() + record.getOutputTokens(),
                inputCost,
                outputCost,
                totalCost,
                record.getTtftMs(),
                record.getTotalLatencyMs(),
                record.isCacheHit(),
                record.getTraceId() != null ? java.util.UUID.fromString(record.getTraceId()) : null,
                record.getComplexityScore()
        );
====
        String sql = """
            INSERT INTO request_audit_logs 
            (query_text, full_prompt, model_name, input_tokens, output_tokens, total_tokens, 
             input_cost, output_cost, total_cost, ttft_ms, total_latency_ms, is_cache_hit, trace_id, complexity_score, tool_calls)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

        jdbcTemplate.update(sql,
                record.getQueryText(),
                record.getFullPrompt(),
                record.getModelName(),
                record.getInputTokens(),
                record.getOutputTokens(),
                record.getInputTokens() + record.getOutputTokens(),
                inputCost,
                outputCost,
                totalCost,
                record.getTtftMs(),
                record.getTotalLatencyMs(),
                record.isCacheHit(),
                record.getTraceId() != null ? java.util.UUID.fromString(record.getTraceId()) : null,
                record.getComplexityScore(),
                record.getToolCallsJson()
        );
>>>>
```

### Task 5: Update AuditServiceTest

**Files:**
- Modify: `src/test/java/org/ai/agent/ddbknowledge/service/AuditServiceTest.java`

- [ ] **Step 1: Update test expectations**

Update all `verify(jdbcTemplate).update(...)` calls to include the extra parameter for `tool_calls`.

### Task 6: Verification & Commit

- [ ] **Step 1: Compile classes**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit changes**

```bash
git add .
git commit -m "feat: add tool_calls column to audit schema and DTOs"
```
