# AuditService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the `AuditService` to calculate LLM request costs and persist audit logs asynchronously to the database.

**Architecture:** The service uses `PricingConfig` for cost lookup and `JdbcTemplate` for DB persistence. It is marked with `@Async` for non-blocking execution.

**Tech Stack:** Java, Spring Boot, Spring JDBC, Project Lombok, JUnit 5, Mockito.

---

### Task 1: Write Failing Test for AuditService

**Files:**
- Create: `src/test/java/org/ai/agent/ddbknowledge/service/AuditServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.ai.agent.ddbknowledge.service;

import org.ai.agent.ddbknowledge.config.PricingConfig;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.Map;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PricingConfig pricingConfig;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        pricingConfig = mock(PricingConfig.class);
        // This will fail to compile initially because AuditService doesn't exist
        auditService = new AuditService(jdbcTemplate, pricingConfig);
    }

    @Test
    void testRecordAudit_CalculatesCostAndInserts() {
        // Setup mock pricing
        PricingConfig.ModelPrice price = new PricingConfig.ModelPrice();
        price.setInputPricePer1m(0.10);
        price.setOutputPricePer1m(0.40);
        when(pricingConfig.getModels()).thenReturn(Map.of("test-model", price));

        AuditRecord record = AuditRecord.builder()
                .queryText("Test query")
                .modelName("test-model")
                .inputTokens(1500)
                .outputTokens(500)
                .ttftMs(150)
                .totalLatencyMs(200)
                .isCacheHit(false)
                .build();

        // Execute
        auditService.recordAudit(record);

        // Verify JDBC call
        // Expected input cost: 1500 * 0.10 / 1M = 0.00015
        // Expected output cost: 500 * 0.40 / 1M = 0.00020
        // Expected total cost: 0.00035
        BigDecimal expectedInputCost = new BigDecimal("0.0001500000");
        BigDecimal expectedOutputCost = new BigDecimal("0.0002000000");
        BigDecimal expectedTotalCost = new BigDecimal("0.0003500000");

        verify(jdbcTemplate).update(
                anyString(),
                eq("Test query"),
                eq("test-model"),
                eq(1500),
                eq(500),
                eq(2000), // total tokens
                eq(expectedInputCost),
                eq(expectedOutputCost),
                eq(expectedTotalCost),
                eq(150L),
                eq(200L),
                eq(false)
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.AuditServiceTest`
Expected: Compilation error (AuditService not found)

### Task 2: Implement AuditService

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java`

- [ ] **Step 1: Create AuditService with minimal implementation**

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
        PricingConfig.ModelPrice prices = pricingConfig.getModels() != null 
                ? pricingConfig.getModels().getOrDefault(record.getModelName(), new PricingConfig.ModelPrice())
                : new PricingConfig.ModelPrice();

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

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.AuditServiceTest`
Expected: PASS

### Task 3: Enable Async Support

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/DdbKnowledgeAgentApplication.java`

- [ ] **Step 1: Add @EnableAsync annotation**

```java
package org.ai.agent.ddbknowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DdbKnowledgeAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DdbKnowledgeAgentApplication.class, args);
    }
}
```

- [ ] **Step 2: Run all tests to ensure no regressions**

Run: `./gradlew test`
Expected: ALL PASS

- [ ] **Step 3: Commit all changes**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java \
        src/test/java/org/ai/agent/ddbknowledge/service/AuditServiceTest.java \
        src/main/java/org/ai/agent/ddbknowledge/DdbKnowledgeAgentApplication.java
git commit -m "feat: implement AuditService with async DB persistence"
```
