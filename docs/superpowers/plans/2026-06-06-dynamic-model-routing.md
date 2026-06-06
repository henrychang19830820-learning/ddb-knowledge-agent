# Dynamic Model Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route incoming queries to different LLM models based on a complexity score evaluated by a lightweight classifier model, and link multiple model calls per request in the audit logs.

**Architecture:** Update `schema.sql` and `AuditRecord` to include `trace_id`. Configure multiple `ChatLanguageModel` beans in `AgentConfig` sharing the same API key. Create `ModelRoutingService` to prompt the classifier. Update `QueryRoutingService` to generate a trace ID, call the router on a cache miss, and use the selected model for generation, passing the trace ID to all audit calls.

**Tech Stack:** Java 21, Spring Boot, LangChain4j, PostgreSQL.

---

### Task 1: Audit Schema & DTO Update (trace_id)

**Files:**
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java`

- [ ] **Step 1: Update schema.sql**

Modify the `request_audit_logs` table creation to add `trace_id UUID`.

```sql
-- In src/main/resources/schema.sql
-- Add trace_id column after audit_id
CREATE TABLE request_audit_logs (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id UUID,
    query_text TEXT,
    -- ... rest of columns
```

- [ ] **Step 2: Apply schema changes manually to DB**

Run: `PGPASSWORD=password psql -h localhost -U user -d ddb_agent -c "ALTER TABLE request_audit_logs ADD COLUMN trace_id UUID;"`

- [ ] **Step 3: Update AuditRecord DTO**

```java
// In src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java
// Add traceId field
    private String traceId;
```

- [ ] **Step 4: Update AuditService**

Update the SQL insert to include `trace_id`.

```java
// In src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java
        String sql = """
            INSERT INTO request_audit_logs 
            (trace_id, query_text, model_name, input_tokens, output_tokens, total_tokens, 
             input_cost, output_cost, total_cost, ttft_ms, total_latency_ms, is_cache_hit)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                record.getTraceId() != null ? java.util.UUID.fromString(record.getTraceId()) : null,
                record.getQueryText(),
                // ... rest of parameters
```

- [ ] **Step 5: Run tests and Commit**

Run: `./gradlew test`

```bash
git add src/main/resources/schema.sql src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java
git commit -m "feat: add trace_id to audit logs for distributed tracing"
```

---

### Task 2: Multi-Model Configuration & Properties

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java`

- [ ] **Step 1: Update application.yml**

Remove the auto-configured `langchain4j.google-ai-studio` block and define our custom properties.

```yaml
# In src/main/resources/application.yml
agent:
  routing:
    complexity-threshold: 5
    classifier-model: "gemini-3.1-flash-lite"
    simple-tier-model: "gemini-3.1-flash-lite"
    complex-tier-model: "gemini-3.5-flash"
  cache:
# ... keep rest
```

- [ ] **Step 2: Update AgentConfig.java**

Define the multiple beans.

```java
// In src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java
import dev.langchain4j.model.googleaistudio.GoogleAiStudioChatModel;
import dev.langchain4j.model.googleaistudio.GoogleAiStudioStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
// ...

    @Value("${GOOGLE_API_KEY}")
    private String apiKey;

    @Value("${agent.routing.classifier-model}")
    private String classifierModelName;

    @Value("${agent.routing.simple-tier-model}")
    private String simpleTierModelName;

    @Value("${agent.routing.complex-tier-model}")
    private String complexTierModelName;

    @Bean
    @Qualifier("classifierModel")
    public ChatLanguageModel classifierModel() {
        return GoogleAiStudioChatModel.builder()
                .apiKey(apiKey)
                .modelName(classifierModelName)
                .build();
    }

    @Bean
    @Qualifier("simpleChatModel")
    public StreamingChatLanguageModel simpleChatModel() {
        return GoogleAiStudioStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(simpleTierModelName)
                .build();
    }

    @Bean
    @Qualifier("complexChatModel")
    public StreamingChatLanguageModel complexChatModel() {
        return GoogleAiStudioStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(complexTierModelName)
                .build();
    }
```

- [ ] **Step 3: Run tests to verify context loads**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.DdbKnowledgeAgentApplicationTests`

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java
git commit -m "feat: configure multiple chat models for dynamic routing"
```

---

### Task 3: ModelRoutingService

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java`
- Create: `src/test/java/org/ai/agent/ddbknowledge/service/ModelRoutingServiceTest.java`

- [ ] **Step 1: Write failing test**

```java
package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ModelRoutingServiceTest {
    @Test
    void testRouteSimpleQuery() {
        ChatLanguageModel mockClassifier = mock(ChatLanguageModel.class);
        AuditService mockAuditService = mock(AuditService.class);
        
        when(mockClassifier.generate(anyList())).thenReturn(
            new Response<>(AiMessage.from("3"), new TokenUsage(10, 1), null)
        );

        ModelRoutingService service = new ModelRoutingService(mockClassifier, mockAuditService);
        service.setClassifierModelName("gemini-lite");
        
        boolean isComplex = service.isComplexQuery("What is DynamoDB?", "trace-123");
        
        org.junit.jupiter.api.Assertions.assertFalse(isComplex);
        verify(mockAuditService).recordAudit(any(AuditRecord.class));
    }
}
```

- [ ] **Step 2: Run test (fails)**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.ModelRoutingServiceTest`

- [ ] **Step 3: Implement ModelRoutingService**

```java
package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelRoutingService {

    @Qualifier("classifierModel")
    private final ChatLanguageModel classifierModel;
    
    private final AuditService auditService;

    @Value("${agent.routing.complexity-threshold:5}")
    private int complexityThreshold;

    @Value("${agent.routing.classifier-model}")
    private String classifierModelName;

    // visible for testing
    void setClassifierModelName(String name) { this.classifierModelName = name; }

    public boolean isComplexQuery(String query, String traceId) {
        long start = System.nanoTime();
        
        String systemPrompt = "You are a query router for a DynamoDB technical assistant. Evaluate the complexity of the following user query on a scale of 1 to 10.\n\n" +
                "Criteria:\n" +
                "- Score 1-5 (Simple): Direct factual lookups, definitions, API limits, basic syntax.\n" +
                "- Score 6-10 (Complex): Data modeling, table design, performance troubleshooting, comparing alternatives.\n\n" +
                "Output ONLY the integer score. Do not explain your reasoning.";

        try {
            Response<AiMessage> response = classifierModel.generate(
                    Arrays.asList(SystemMessage.from(systemPrompt), UserMessage.from(query))
            );
            
            long latency = (System.nanoTime() - start) / 1_000_000;
            String textResponse = response.content().text().trim();
            int score = Integer.parseInt(textResponse);
            
            log.info("Query complexity score: {} for query: '{}'", score, query);
            
            int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
            int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

            auditService.recordAudit(AuditRecord.builder()
                    .traceId(traceId)
                    .queryText("CLASSIFIER: " + query)
                    .modelName(classifierModelName)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .ttftMs(latency)
                    .totalLatencyMs(latency)
                    .isCacheHit(false)
                    .build());

            return score > complexityThreshold;

        } catch (Exception e) {
            log.error("Failed to classify query, defaulting to complex.", e);
            long latency = (System.nanoTime() - start) / 1_000_000;
            auditService.recordAudit(AuditRecord.builder()
                    .traceId(traceId)
                    .queryText("CLASSIFIER ERROR: " + query)
                    .modelName(classifierModelName)
                    .inputTokens(0)
                    .outputTokens(0)
                    .ttftMs(latency)
                    .totalLatencyMs(latency)
                    .isCacheHit(false)
                    .build());
            return true; // Default to complex on error to preserve quality
        }
    }
}
```

- [ ] **Step 4: Run test (passes)**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.ModelRoutingServiceTest`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java src/test/java/org/ai/agent/ddbknowledge/service/ModelRoutingServiceTest.java
git commit -m "feat: implement ModelRoutingService for LLM-based complexity scoring"
```

---

### Task 4: Integrate Routing into QueryRoutingService

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Inject dependencies and remove old single model**

Remove `ChatLanguageModel` and `StreamingChatLanguageModel`.
Inject `ModelRoutingService`, `@Qualifier("simpleChatModel") StreamingChatLanguageModel simpleChatModel`, `@Qualifier("complexChatModel") StreamingChatLanguageModel complexChatModel`.
Add `@Value` for `simpleTierModelName` and `complexTierModelName`.

- [ ] **Step 2: Update askStreaming() logic**

1. Generate `traceId` using `java.util.UUID.randomUUID().toString()`.
2. Pass `traceId` to Cache Hit audit record.
3. On Cache Miss, call `boolean isComplex = modelRoutingService.isComplexQuery(query, traceId);`.
4. Select model: `StreamingChatLanguageModel selectedModel = isComplex ? complexChatModel : simpleChatModel;`
5. Select name: `String selectedModelName = isComplex ? complexTierModelName : simpleTierModelName;`
6. Call `selectedModel.generate(...)`.
7. Pass `traceId` and `selectedModelName` to the Generator's audit records in the callback.

- [ ] **Step 3: Update ask() logic**

*Note: For `ask()` (non-streaming), you'll need standard versions of the ChatModels. For simplicity and to avoid too many beans, you can either add standard ChatModel beans or just convert the app to only use `askStreaming` and remove `ask()`. Given the UI uses streaming, let's remove `ask()` entirely to simplify the codebase.*

Delete the `public String ask(String query)` method from `QueryRoutingService` and `QueryController` to keep things DRY.

- [ ] **Step 4: Run tests**

Fix any broken tests (e.g. if `QueryControllerTest` used `ask()`).
Run: `./gradlew test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java src/main/java/org/ai/agent/ddbknowledge/web/QueryController.java
git commit -m "feat: integrate dynamic routing into QueryRoutingService and add trace_id"
```
