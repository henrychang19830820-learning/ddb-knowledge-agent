# Hybrid Entity-Match Guardrail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a two-tier guardrail (Regex + LLM) for the semantic cache to prevent technical "drift" in DynamoDB queries.

**Architecture:** Use a `EntityMatcher` for fast regex keyword extraction, followed by an `EntityVerifier` (Gemini Flash Lite) as a fallback when intent equivalence is ambiguous.

**Tech Stack:** Java 21, Spring Boot, LangChain4j, Google Gemini.

---

### Task 1: Update Cache Metadata Schema

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java:93-100`

- [ ] **Step 1: Update `cacheStore` to include `detected_entities` column**

```java
    @Bean("cacheStore")
    public EmbeddingStore<TextSegment> cacheStore() {
        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("ddb_agent")
                .user("user")
                .password("password")
                .table("ddb_semantic_cache")
                .dimension(384)
                .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                        .storageMode(MetadataStorageMode.COLUMN_PER_KEY)
                        .columnDefinitions(java.util.Arrays.asList(
                            "original_query TEXT",
                            "detected_entities TEXT"
                        ))
                        .build())
                .build();
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java
git commit -m "feat: add detected_entities column to semantic cache metadata"
```

### Task 2: Implement `EntityMatcher` (Regex Tier)

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityMatcher.java`

- [ ] **Step 1: Create `EntityMatcher` with core DynamoDB keyword patterns**

```java
package org.ai.agent.ddbknowledge.guardrail;

import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EntityMatcher {
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
        "\\b(GSI|LSI|Global Secondary Index|Local Secondary Index|TTL|Time To Live|DAX|Streams|PITR|Global Tables|RCU|WCU|On-Demand|Provisioned|PartiQL|BatchWriteItem|BatchGetItem|PutItem|GetItem|UpdateItem|DeleteItem|Query|Scan|CreateTable|UpdateTable|DeleteTable|DescribeTable|Transactions|TransactWriteItems|TransactGetItems|IAM|Resource-based policy|ConditionExpression|FilterExpression|ProjectionExpression|Partition Key|Sort Key|Hash Key|Range Key|LSN|SequenceNumber)\\b",
        Pattern.CASE_INSENSITIVE
    );

    public Set<String> extractEntities(String text) {
        Set<String> entities = new HashSet<>();
        if (text == null) return entities;
        Matcher matcher = KEYWORD_PATTERN.matcher(text);
        while (matcher.find()) {
            entities.add(matcher.group().toUpperCase());
        }
        return entities;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityMatcher.java
git commit -m "feat: implement Regex-based EntityMatcher"
```

### Task 3: Implement `EntityVerifier` (LLM Tier)

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityVerifier.java`

- [ ] **Step 1: Create `EntityVerifier` using `ChatLanguageModel`**

```java
package org.ai.agent.ddbknowledge.guardrail;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class EntityVerifier {
    private final ChatLanguageModel classifierModel;

    public EntityVerifier(@Qualifier("classifierModel") ChatLanguageModel classifierModel) {
        this.classifierModel = classifierModel;
    }

    public boolean areEquivalent(String query1, String query2) {
        String prompt = """
            You are a technical validator for Amazon DynamoDB.
            Compare the two user queries below.
            Determine if they target the same specific API, feature, index type (GSI vs LSI), or technical constraint.
            If they ask about different things (e.g., one about GSI and another about LSI), they are NOT equivalent.
            If they use different words for the same thing (e.g., "partition key" vs "PK"), they ARE equivalent.

            Query 1: %s
            Query 2: %s

            Output ONLY "EQUIVALENT" or "DIFFERENT".
            """.formatted(query1, query2);

        String result = classifierModel.generate(prompt).trim();
        return "EQUIVALENT".equalsIgnoreCase(result);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityVerifier.java
git commit -m "feat: implement LLM-based EntityVerifier"
```

### Task 4: Create `EntityGuardrailService`

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/service/EntityGuardrailService.java`

- [ ] **Step 1: Implement the hybrid logic**

```java
package org.ai.agent.ddbknowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.guardrail.EntityMatcher;
import org.ai.agent.ddbknowledge.guardrail.EntityVerifier;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class EntityGuardrailService {
    private final EntityMatcher matcher;
    private final EntityVerifier verifier;

    public boolean isValid(String newQuery, String cachedQuery, String cachedEntitiesStr) {
        Set<String> newEntities = matcher.extractEntities(newQuery);
        Set<String> cachedEntities = matcher.extractEntities(cachedEntitiesStr != null ? cachedEntitiesStr : "");

        // Tier 1: Regex Match
        if (newEntities.equals(cachedEntities)) {
            log.info("Guardrail: Exact entity match found: {}", newEntities);
            return true;
        }

        // Tier 2: LLM Fallback
        log.info("Guardrail: Entity mismatch ({} vs {}), falling back to LLM", newEntities, cachedEntities);
        boolean equivalent = verifier.areEquivalent(newQuery, cachedQuery);
        log.info("Guardrail: LLM verification result: {}", equivalent ? "EQUIVALENT" : "DIFFERENT");
        return equivalent;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/EntityGuardrailService.java
git commit -m "feat: implement EntityGuardrailService"
```

### Task 5: Integrate Guardrail into `QueryRoutingService`

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Inject `EntityGuardrailService` and `EntityMatcher`**

```java
    private final EntityGuardrailService entityGuardrailService;
    private final EntityMatcher entityMatcher;

    public QueryRoutingService(ModelRoutingService modelRoutingService,
                              @Qualifier("simpleChatModel") StreamingChatLanguageModel simpleChatModel,
                              @Qualifier("mediumChatModel") StreamingChatLanguageModel mediumChatModel,
                              @Qualifier("complexChatModel") StreamingChatLanguageModel complexChatModel,
                              EmbeddingModel embeddingModel,
                              @Qualifier("cacheStore") EmbeddingStore<TextSegment> cacheStore,
                              DocumentationTool documentationTool,
                              AuditService auditService,
                              EntityGuardrailService entityGuardrailService,
                              EntityMatcher entityMatcher) {
        this.modelRoutingService = modelRoutingService;
        this.simpleChatModel = simpleChatModel;
        this.mediumChatModel = mediumChatModel;
        this.complexChatModel = complexChatModel;
        this.embeddingModel = embeddingModel;
        this.cacheStore = cacheStore;
        this.documentationTool = documentationTool;
        this.auditService = auditService;
        this.entityGuardrailService = entityGuardrailService;
        this.entityMatcher = entityMatcher;
    }
```

- [ ] **Step 2: Apply guardrail check in `askStreaming`**

```java
                if (!cacheMatches.isEmpty()) {
                    TextSegment match = cacheMatches.get(0).embedded();
                    String cachedAnswer = match.text();
                    String cachedQuery = match.metadata().getString("original_query");
                    String cachedEntities = match.metadata().getString("detected_entities");

                    if (entityGuardrailService.isValid(query, cachedQuery, cachedEntities)) {
                        log.info("CACHE_HIT_VALIDATED [traceId={}]: Score {}", traceId, cacheMatches.get(0).score());
                        // ... existing cache hit logic ...
                    } else {
                        log.warn("CACHE_HIT_REJECTED [traceId={}]: Semantic drift detected via guardrail", traceId);
                    }
                }
```

- [ ] **Step 3: Store entities when updating cache**

```java
                            log.info("Updating semantic cache with new answer [traceId={}]", traceId);
                            Metadata metadata = new Metadata();
                            metadata.put("original_query", query);
                            metadata.put("detected_entities", String.join(",", entityMatcher.extractEntities(query)));
                            cacheStore.add(queryEmbedding, TextSegment.from(fullResponse.toString(), metadata));
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: integrate EntityGuardrailService into QueryRoutingService"
```

### Task 6: Test Semantic Drift Prevention

**Files:**
- Create: `src/test/java/org/ai/agent/ddbknowledge/guardrail/EntityGuardrailTest.java`

- [ ] **Step 1: Write integration test for drift prevention**

```java
package org.ai.agent.ddbknowledge.guardrail;

import org.ai.agent.ddbknowledge.service.EntityGuardrailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class EntityGuardrailTest {
    @Autowired
    private EntityGuardrailService guardrailService;

    @Test
    void shouldRejectDrift_GsiVsLsi() {
        String newQuery = "How to create a GSI?";
        String cachedQuery = "How to create an LSI?";
        String cachedEntities = "LSI";
        
        boolean isValid = guardrailService.isValid(newQuery, cachedQuery, cachedEntities);
        assertThat(isValid).isFalse();
    }

    @Test
    void shouldAcceptSynonym_PartitionKeyVsPk() {
        String newQuery = "What is a PK?";
        String cachedQuery = "What is a partition key?";
        String cachedEntities = "PARTITION KEY";
        
        // This should trigger LLM fallback and return true
        boolean isValid = guardrailService.isValid(newQuery, cachedQuery, cachedEntities);
        assertThat(isValid).isTrue();
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.guardrail.EntityGuardrailTest`

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/ai/agent/ddbknowledge/guardrail/EntityGuardrailTest.java
git commit -m "test: add EntityGuardrail integration tests"
```
