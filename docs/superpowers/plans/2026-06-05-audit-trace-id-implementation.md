# Audit Schema & DTO Update (trace_id) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `trace_id` to the audit logs to link multiple LLM calls per user request for Dynamic Model Routing.

**Architecture:** Update the database schema, DTO, and service layer to include and handle `trace_id`.

**Tech Stack:** Java, Spring Boot, PostgreSQL, JDBC Template, Lombok.

---

### Task 1: Research & Reproduce Failure (RED)

**Files:**
- Test: `src/test/java/org/ai/agent/ddbknowledge/service/AuditServiceTest.java`

- [ ] **Step 1: Update AuditServiceTest.java to include traceId**

Update the existing tests to include `traceId` in the `AuditRecord` and verify it's passed to `jdbcTemplate.update`. This will fail to compile initially because `AuditRecord` doesn't have `traceId` yet.

```java
    @Test
    void testRecordAudit_IncludesTraceId() {
        // Setup mock pricing
        PricingConfig.ModelPrice price = new PricingConfig.ModelPrice();
        price.setInputPricePer1m(0.10);
        price.setOutputPricePer1m(0.40);
        when(pricingConfig.getModels()).thenReturn(Map.of("test-model", price));

        String traceId = "550e8400-e29b-41d4-a716-446655440000";
        AuditRecord record = AuditRecord.builder()
                .queryText("Test query")
                .modelName("test-model")
                .inputTokens(1000)
                .outputTokens(1000)
                .traceId(traceId)
                .build();

        // Execute
        auditService.recordAudit(record);

        // Verify JDBC call includes trace_id (should be the last parameter or as specified in SQL)
        verify(jdbcTemplate).update(
                contains("trace_id"),
                eq("Test query"),
                eq("test-model"),
                eq(1000),
                eq(1000),
                eq(2000),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyLong(),
                anyLong(),
                anyBoolean(),
                eq(java.util.UUID.fromString(traceId))
        );
    }
```

- [ ] **Step 2: Run tests to verify compilation failure**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.AuditServiceTest`
Expected: Compilation failure (cannot find symbol `traceId` in `AuditRecord.AuditRecordBuilder`).

---

### Task 2: Update DTO (GREEN)

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java`

- [ ] **Step 1: Add traceId field to AuditRecord.java**

```java
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
    private String traceId; // Add this
}
```

- [ ] **Step 2: Run tests to verify failure (Method not found in Service)**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.AuditServiceTest`
Expected: Failure in `testRecordAudit_IncludesTraceId` because `AuditService` does not yet handle `traceId` or the SQL doesn't match.

---

### Task 3: Update Schema (SQL & DB)

**Files:**
- Modify: `src/main/resources/schema.sql`

- [ ] **Step 1: Update schema.sql**

```sql
-- Audit Logs for Request tracking
DROP TABLE IF EXISTS request_audit_logs;
CREATE TABLE request_audit_logs (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id UUID, -- Add this
    query_text TEXT,
    ...
```

- [ ] **Step 2: Apply DB Change manually**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "ALTER TABLE request_audit_logs ADD COLUMN trace_id UUID;"`
Expected: Success.

---

### Task 4: Update AuditService (GREEN)

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java`

- [ ] **Step 1: Update recordAudit method in AuditService.java**

Update the SQL and the `jdbcTemplate.update` parameters.

```java
        String sql = """
            INSERT INTO request_audit_logs 
            (query_text, model_name, input_tokens, output_tokens, total_tokens, 
             input_cost, output_cost, total_cost, ttft_ms, total_latency_ms, is_cache_hit, trace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                record.getQueryText(),
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
                record.getTraceId() != null ? java.util.UUID.fromString(record.getTraceId()) : null
        );
```

- [ ] **Step 2: Run tests to verify success**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.AuditServiceTest`
Expected: All tests pass.

---

### Task 5: Final Verification & Commit

- [ ] **Step 1: Run all tests**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 2: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit changes**

```bash
git add src/main/resources/schema.sql src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java src/test/java/org/ai/agent/ddbknowledge/service/AuditServiceTest.java
git commit -m "feat: add trace_id to audit logs for distributed tracing"
```
