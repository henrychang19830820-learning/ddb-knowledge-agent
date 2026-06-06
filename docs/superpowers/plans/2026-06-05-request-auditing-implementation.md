# Request Auditing & Cost Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a robust auditing system to record token usage, model costs, and latency (including TTFT) for every user request in a PostgreSQL table.

**Architecture:** Use a new `AuditService` to handle cost calculations and asynchronous database persistence. Integrate timing logic directly into `QueryRoutingService` to capture precise Time To First Token (TTFT) and total latency.

**Tech Stack:** Java 21, Spring Boot, Spring Data JDBC, PostgreSQL, LangChain4j.

---

### Task 1: Database Schema Update

**Files:**
- Modify: `src/main/resources/schema.sql`

- [ ] **Step 1: Add request_audit_logs table to schema.sql**

Update the schema to include the new audit table with cost and latency columns.

```sql
-- Audit Logs for Request tracking
DROP TABLE IF EXISTS request_audit_logs;
CREATE TABLE request_audit_logs (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_text TEXT,
    model_name TEXT,
    
    -- Token Metrics
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    total_tokens INTEGER DEFAULT 0,
    
    -- Cost Metrics (USD)
    input_cost NUMERIC(18, 10) DEFAULT 0,
    output_cost NUMERIC(18, 10) DEFAULT 0,
    total_cost NUMERIC(18, 10) DEFAULT 0,
    
    -- Latency Metrics (milliseconds)
    ttft_ms BIGINT,
    total_latency_ms BIGINT,
    
    -- Metadata
    is_cache_hit BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_created_at ON request_audit_logs(created_at);
```

- [ ] **Step 2: Apply schema changes manually**

Run: `PGPASSWORD=password psql -h localhost -U user -d ddb_agent -c "CREATE TABLE request_audit_logs (...); CREATE INDEX ...;"` (or run the full script).

- [ ] **Step 3: Verify table creation**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "\d request_audit_logs"`
Expected: Table structure matches the spec.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/schema.sql
git commit -m "feat: add request_audit_logs table to schema"
```

---

### Task 2: Pricing Configuration & Audit DTO

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java`
- Create: `src/main/java/org/ai/agent/ddbknowledge/config/PricingConfig.java`

- [ ] **Step 1: Update application.yml with pricing**

```yaml
agent:
  pricing:
    models:
      gemini-3.5-flash:
        input-price-per-1m: 0.10
        output-price-per-1m: 0.40
      gemini-3-flash:
        input-price-per-1m: 0.075
        output-price-per-1m: 0.30
      gemini-3.1-flash-lite:
        input-price-per-1m: 0.05
        output-price-per-1m: 0.20
      gemini-2.5-flash:
        input-price-per-1m: 0.075
        output-price-per-1m: 0.30
      gemini-2.5-flash-lite:
        input-price-per-1m: 0.05
        output-price-per-1m: 0.20
```

- [ ] **Step 2: Create PricingConfig class**

```java
package org.ai.agent.ddbknowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "agent.pricing")
@Data
public class PricingConfig {
    private Map<String, ModelPrice> models;

    @Data
    public static class ModelPrice {
        private double inputPricePer1m;
        private double outputPricePer1m;
    }
}
```

- [ ] **Step 3: Create AuditRecord DTO**

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
}
```

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "feat: add pricing configuration and audit DTO"
```

---

### Task 3: AuditService Implementation

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java`

- [ ] **Step 1: Write failing test for AuditService (Cost calculation)**

Create `src/test/java/org/ai/agent/ddbknowledge/service/AuditServiceTest.java` to verify the math for small fractions of a dollar.

- [ ] **Step 2: Implement AuditService**

```java
package org.ai.agent.ddbknowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.config.PricingConfig;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final PricingConfig pricingConfig;

    @Async
    public void recordAudit(AuditRecord record) {
        PricingConfig.ModelPrice prices = pricingConfig.getModels()
                .getOrDefault(record.getModelName(), new PricingConfig.ModelPrice());

        BigDecimal inputCost = calculateCost(record.getInputTokens(), prices.getInputPricePer1m());
        BigDecimal outputCost = calculateCost(record.getOutputTokens(), prices.getOutputPricePer1m());
        BigDecimal totalCost = inputCost.add(outputCost);

        String sql = """
            INSERT INTO request_audit_logs 
            (query_text, model_name, input_tokens, output_tokens, total_tokens, 
             input_cost, output_cost, total_cost, ttft_ms, total_latency_ms, is_cache_hit)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                record.isCacheHit()
        );
    }

    private BigDecimal calculateCost(int tokens, double pricePer1m) {
        return BigDecimal.valueOf(tokens)
                .multiply(BigDecimal.valueOf(pricePer1m))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 3: Enable Async in DdbKnowledgeAgentApplication**

Add `@EnableAsync` to the main class.

- [ ] **Step 4: Run test to verify passes**

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "feat: implement AuditService with async DB persistence"
```

---

### Task 4: QueryRoutingService Integration

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Inject AuditService and capture start times**

```java
// Capture System.nanoTime() at method start
long startTime = System.nanoTime();
```

- [ ] **Step 2: Capture TTFT and Tokens in askStreaming**

Update the `StreamingResponseHandler` to:
1.  Set `firstTokenTime` on the first `onNext` call.
2.  Calculate `totalTime` and extract `TokenUsage` in `onComplete`.
3.  Call `auditService.recordAudit(...)`.

- [ ] **Step 3: Capture Metrics in ask (Non-Streaming)**

1.  Capture `totalTime` after `chatModel.generate()` returns.
2.  Extract `TokenUsage` from the `Response` object.
3.  Call `auditService.recordAudit(...)`.

- [ ] **Step 4: Handle Cache Hits**

Call `auditService.recordAudit` with `isCacheHit=true` and `0` tokens.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: integrate audit logging into QueryRoutingService"
```

---

### Task 5: Verification & Audit Inspection

- [ ] **Step 1: Start Application**

Run: `./gradlew bootRun`

- [ ] **Step 2: Trigger several requests (Hits and Misses)**

- [ ] **Step 3: Inspect Audit Logs**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "SELECT * FROM request_audit_logs;"`
Verify that `ttft_ms` is populated and `total_cost` is calculated correctly.

- [ ] **Step 4: Final commit**

```bash
git commit -am "chore: finalize request auditing implementation"
```
