# KeywordSearchRepository Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a repository to perform keyword search using Postgres Full-Text Search (FTS) for a Hybrid RAG system.

**Architecture:** Use `JdbcTemplate` to execute SQL queries against the `ddb_knowledge_chunks` table, leveraging the `content_tokens` column and `ts_rank` for scoring. Results will be mapped to LangChain4j's `EmbeddingMatch<TextSegment>`.

**Tech Stack:** Java 21, Spring Boot 3.3.0, Spring Data JPA (JdbcTemplate), LangChain4j Core, Jackson Databind.

---

### Task 1: Create failing test for KeywordSearchRepository

**Files:**
- Create: `src/test/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

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
        // Assumes data is already ingested (DynamoDB docs are present)
        List<?> results = repository.search("DynamoDB", 5);
        assertFalse(results.isEmpty(), "Should find results for 'DynamoDB'");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.repository.KeywordSearchRepositoryTest`
Expected: FAIL (Compilation error: KeywordSearchRepository not found)

### Task 2: Implement KeywordSearchRepository

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepository.java`

- [ ] **Step 1: Create the repository directory**

Run: `mkdir -p src/main/java/org/ai/agent/ddbknowledge/repository`

- [ ] **Step 2: Implement the repository**

```java
package org.ai.agent.ddbknowledge.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class KeywordSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KeywordSearchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<EmbeddingMatch<TextSegment>> search(String query, int limit) {
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

            Map<String, String> metadata = Map.of();
            try {
                if (metadataJson != null) {
                    metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, String>>() {});
                }
            } catch (JsonProcessingException e) {
                // Log error or handle appropriately
            }

            return new EmbeddingMatch<>(
                    score,
                    id,
                    null, // No embedding vector needed for keyword search results
                    TextSegment.from(text, dev.langchain4j.data.document.Metadata.from(metadata))
            );
        }, query, query, limit);
    }
}
```

### Task 3: Verify the implementation

- [ ] **Step 1: Run the test again**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.repository.KeywordSearchRepositoryTest`
Expected: PASS (if data is present)

*Note: If data is not present, I might need to ingest some sample data first or adjust the test to use a mock if I can't rely on existing data.*

### Task 4: Finalize and Commit

- [ ] **Step 1: Commit the changes**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepository.java \
        src/test/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepositoryTest.java
git commit -m "feat: implement KeywordSearchRepository for Postgres FTS"
```
