# ModelRoutingService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `ModelRoutingService` to score query complexity using an LLM (classifier model) and log audit records.

**Architecture:** The service will use a `@Qualifier("classifierModel") ChatLanguageModel` to evaluate queries. It will prompt the LLM for a 1-10 score, compare it against a threshold, and log the results via `AuditService`.

**Tech Stack:** Java 21, Spring Boot 3.3, LangChain4j, Mockito, JUnit 5.

---

### Task 1: Create failing test for ModelRoutingService

**Files:**
- Create: `src/test/java/org/ai/agent/ddbknowledge/service/ModelRoutingServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelRoutingServiceTest {

    @Mock
    private ChatLanguageModel mockClassifier;

    @Mock
    private AuditService mockAuditService;

    @Test
    void testRouteSimpleQuery() {
        when(mockClassifier.generate(anyList())).thenReturn(
            new Response<>(AiMessage.from("3"), new TokenUsage(10, 1), FinishReason.STOP)
        );

        ModelRoutingService service = new ModelRoutingService(mockClassifier, mockAuditService);
        service.setClassifierModelName("gemini-lite");
        service.setComplexityThreshold(5);
        
        boolean isComplex = service.isComplexQuery("What is DynamoDB?", "00000000-0000-0000-0000-000000000123");
        
        assertFalse(isComplex);
        
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(mockAuditService).recordAudit(captor.capture());
        
        AuditRecord record = captor.getValue();
        assertEquals("What is DynamoDB?", record.getQueryText());
        assertEquals("gemini-lite", record.getModelName());
        assertEquals("00000000-0000-0000-0000-000000000123", record.getTraceId());
    }

    @Test
    void testRouteComplexQuery() {
        when(mockClassifier.generate(anyList())).thenReturn(
            new Response<>(AiMessage.from("8"), new TokenUsage(15, 2), FinishReason.STOP)
        );

        ModelRoutingService service = new ModelRoutingService(mockClassifier, mockAuditService);
        service.setClassifierModelName("gemini-lite");
        service.setComplexityThreshold(5);
        
        boolean isComplex = service.isComplexQuery("How do I implement a global secondary index with overloading for a multi-tenant application?", "00000000-0000-0000-0000-000000000456");
        
        assertTrue(isComplex);
        verify(mockAuditService).recordAudit(any(AuditRecord.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.ModelRoutingServiceTest`
Expected: FAIL with "Compilation failed" (ModelRoutingService doesn't exist yet)

### Task 2: Implement ModelRoutingService

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java`

- [ ] **Step 1: Write implementation**

```java
package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@Slf4j
public class ModelRoutingService {

    private final ChatLanguageModel classifierModel;
    private final AuditService auditService;

    @Setter
    @Value("${agent.routing.complexity-threshold:5}")
    private int complexityThreshold;

    @Setter
    @Value("${agent.routing.classifier-model}")
    private String classifierModelName;

    public ModelRoutingService(@Qualifier("classifierModel") ChatLanguageModel classifierModel, 
                               AuditService auditService) {
        this.classifierModel = classifierModel;
        this.auditService = auditService;
    }

    public boolean isComplexQuery(String query, String traceId) {
        String prompt = """
                Evaluate the complexity of the following user query regarding Amazon DynamoDB on a scale of 1 to 10.
                1-3: Simple, factual, or basic definition questions.
                4-6: Intermediate questions involving single-table design basics or specific API usage.
                7-10: Complex architectural, optimization, or multi-faceted design questions.
                
                Output ONLY the integer score.
                
                Query: %s
                """.formatted(query);

        long startTime = System.currentTimeMillis();
        try {
            Response<AiMessage> response = classifierModel.generate(Collections.singletonList(UserMessage.from(prompt)));
            long duration = System.currentTimeMillis() - startTime;

            String scoreText = response.content().text().trim();
            int score = Integer.parseInt(scoreText.replaceAll("[^0-9]", ""));
            
            log.info("Query complexity score: {} for query: '{}'", score, query);

            auditService.recordAudit(AuditRecord.builder()
                    .queryText(query)
                    .modelName(classifierModelName)
                    .inputTokens(response.tokenUsage().inputTokenCount())
                    .outputTokens(response.tokenUsage().outputTokenCount())
                    .totalLatencyMs(duration)
                    .traceId(traceId)
                    .build());

            return score > complexityThreshold;
        } catch (Exception e) {
            log.error("Error evaluating query complexity, defaulting to complex", e);
            return true;
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.ModelRoutingServiceTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java src/test/java/org/ai/agent/ddbknowledge/service/ModelRoutingServiceTest.java
git commit -m "feat: implement ModelRoutingService for LLM-based complexity scoring"
```
