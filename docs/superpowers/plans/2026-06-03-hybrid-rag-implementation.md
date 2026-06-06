# Hybrid RAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Hybrid RAG by combining Vector Search (pgvector) and Keyword Search (Postgres Full-Text Search) using Reciprocal Rank Fusion (RRF).

**Architecture:** Use a Postgres Generated Column for automated keyword tokenization. Create a `HybridSearchService` that executes both vector and keyword searches in parallel/sequence and merges results using RRF. Update `QueryRoutingService` to use this hybrid search.

**Tech Stack:** Spring Boot, Spring Data JDBC (for FTS), LangChain4j (pgvector), PostgreSQL.

---

### Task 1: Database Schema Update

**Files:**
- Modify: `src/main/resources/schema.sql`

- [ ] **Step 1: Add generated column and GIN index to schema.sql**

Update `ddb_knowledge_chunks` table definition to include `content_tokens`.

```sql
ALTER TABLE ddb_knowledge_chunks 
ADD COLUMN content_tokens tsvector 
GENERATED ALWAYS AS (to_tsvector('english', text)) STORED;

CREATE INDEX IF NOT EXISTS ddb_knowledge_chunks_content_tokens_idx 
ON ddb_knowledge_chunks USING GIN(content_tokens);
```

- [ ] **Step 2: Apply schema changes manually for current session**

Run: `psql -h localhost -U user -d ddb_agent -c "ALTER TABLE ddb_knowledge_chunks ADD COLUMN content_tokens tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED;"`
Run: `psql -h localhost -U user -d ddb_agent -c "CREATE INDEX ddb_knowledge_chunks_content_tokens_idx ON ddb_knowledge_chunks USING GIN(content_tokens);"`

- [ ] **Step 3: Verify column and index creation**

Run: `psql -h localhost -U user -d ddb_agent -c "\d ddb_knowledge_chunks"`
Expected: `content_tokens` column present with type `tsvector` and index `ddb_knowledge_chunks_content_tokens_idx` listed.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/schema.sql
git commit -m "feat: add FTS generated column and GIN index to schema"
```

---

### Task 2: KeywordSearchRepository Implementation

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepository.java`

- [ ] **Step 1: Write the failing test for KeywordSearchRepository**

Create `src/test/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepositoryTest.java`

```java
package org.ai.agent.ddbknowledge.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class KeywordSearchRepositoryTest {
    @Autowired(required = false)
    KeywordSearchRepository repository;

    @Test
    void testKeywordSearch() {
        assertNotNull(repository, "Repository should be defined");
        // Assumes data is already ingested from previous turns
        List<?> results = repository.search("DynamoDB", 5);
        assertFalse(results.isEmpty(), "Should find results for 'DynamoDB'");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.repository.KeywordSearchRepositoryTest`
Expected: Compilation error or failure.

- [ ] **Step 3: Implement KeywordSearchRepository**

```java
package org.ai.agent.ddbknowledge.repository;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class KeywordSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        String sql = """
            SELECT embedding_id, text, metadata, 
                   ts_rank(content_tokens, plainto_tsquery('english', ?)) as score
            FROM ddb_knowledge_chunks
            WHERE content_tokens @@ plainto_tsquery('english', ?)
            ORDER BY score DESC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String id = rs.getString("embedding_id");
            String text = rs.getString("text");
            String metadataJson = rs.getString("metadata");
            double score = rs.getDouble("score");

            Metadata metadata = new Metadata();
            try {
                Map<String, Object> map = objectMapper.readValue(metadataJson, Map.class);
                map.forEach((k, v) -> metadata.put(k, v.toString()));
            } catch (Exception e) {
                // Ignore parsing errors
            }

            return new EmbeddingMatch<>(score, id, null, TextSegment.from(text, metadata));
        }, query, query, maxResults);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.repository.KeywordSearchRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepository.java
git commit -m "feat: implement KeywordSearchRepository for Postgres FTS"
```

---

### Task 3: HybridSearchService Implementation (RRF)

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/service/HybridSearchService.java`

- [ ] **Step 1: Write the failing test for HybridSearchService**

Create `src/test/java/org/ai/agent/ddbknowledge/service/HybridSearchServiceTest.java`

```java
package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HybridSearchServiceTest {
    @Autowired
    HybridSearchService hybridSearchService;

    @Test
    void testHybridSearch() {
        List<EmbeddingMatch<TextSegment>> results = hybridSearchService.search("What is DynamoDB?", 5);
        assertNotNull(results);
        assertTrue(results.size() <= 5);
        assertFalse(results.isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.HybridSearchServiceTest`

- [ ] **Step 3: Implement HybridSearchService with RRF**

```java
package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import lombok.RequiredArgsConstructor;
import org.ai.agent.ddbknowledge.repository.KeywordSearchRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HybridSearchService {

    @Qualifier("knowledgeStore")
    private final EmbeddingStore<TextSegment> knowledgeStore;
    private final KeywordSearchRepository keywordSearchRepository;
    private final EmbeddingModel embeddingModel;

    private static final int RRF_K = 60;

    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        // 1. Vector Search
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest vectorRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults * 2) // Get more for better fusion
                .build();
        List<EmbeddingMatch<TextSegment>> vectorMatches = knowledgeStore.search(vectorRequest).matches();

        // 2. Keyword Search
        List<EmbeddingMatch<TextSegment>> keywordMatches = keywordSearchRepository.search(query, maxResults * 2);

        // 3. RRF Fusion
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, EmbeddingMatch<TextSegment>> idToMatch = new HashMap<>();

        // Rank Vector Matches
        for (int i = 0; i < vectorMatches.size(); i++) {
            EmbeddingMatch<TextSegment> match = vectorMatches.get(i);
            String id = match.embeddingId();
            rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (RRF_K + i + 1));
            idToMatch.put(id, match);
        }

        // Rank Keyword Matches
        for (int i = 0; i < keywordMatches.size(); i++) {
            EmbeddingMatch<TextSegment> match = keywordMatches.get(i);
            String id = match.embeddingId();
            rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (RRF_K + i + 1));
            if (!idToMatch.containsKey(id)) {
                idToMatch.put(id, match);
            }
        }

        // 4. Sort and Limit
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(entry -> {
                    EmbeddingMatch<TextSegment> original = idToMatch.get(entry.getKey());
                    return new EmbeddingMatch<>(entry.getValue(), original.embeddingId(), original.embedding(), original.embedded());
                })
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.service.HybridSearchServiceTest`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/HybridSearchService.java
git commit -m "feat: implement HybridSearchService with RRF fusion"
```

---

### Task 4: Update QueryRoutingService

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Inject HybridSearchService into QueryRoutingService**

```java
// Add to fields
private final HybridSearchService hybridSearchService;

// Update constructor
public QueryRoutingService(..., HybridSearchService hybridSearchService) {
    ...
    this.hybridSearchService = hybridSearchService;
}
```

- [ ] **Step 2: Update ask() and askStreaming() to use hybridSearchService**

Replace `knowledgeStore.search(knowledgeSearchRequest).matches()` with `hybridSearchService.search(query, 5)`.

- [ ] **Step 3: Verify with existing tests**

Run: `./gradlew test`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: integrate HybridSearchService into QueryRoutingService"
```

---

### Task 5: Final E2E Verification

- [ ] **Step 1: Start Application**

Run: `./gradlew bootRun` (background)

- [ ] **Step 2: Test specific technical keyword**

Run: `curl -G --data-urlencode "question=What is GSI?" http://localhost:8090/ask`
Verify the answer is accurate and mentions Global Secondary Indexes.

- [ ] **Step 3: Cleanup tests**

Remove temporary test files if needed (or keep them as permanent regression tests).

- [ ] **Step 4: Commit final changes**

```bash
git commit -am "chore: finalize Hybrid RAG implementation"
```
