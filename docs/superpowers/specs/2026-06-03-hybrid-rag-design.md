# Design Spec: Hybrid RAG with Postgres FTS and RRF

**Date**: 2026-06-03
**Topic**: Transitioning from Vector-only RAG to Hybrid RAG (Vector + Keyword)
**Status**: Draft

## 1. Objective
Enhance the retrieval quality of the DDB Knowledge Agent by combining semantic vector search with linguistic keyword search. This "Hybrid" approach ensures that technical terms (e.g., "GSI", "LSI", "HNSW") and exact phrases are retrieved reliably, while still maintaining the conceptual understanding provided by embeddings.

## 2. Architecture Overview
The system will use **PostgreSQL** as the single source of truth for both retrieval types:
- **Vector Search**: Handled by `pgvector` (existing).
- **Keyword Search**: Handled by Postgres **Full-Text Search (FTS)** using a generated `tsvector` column.
- **Fusion**: Results from both searches will be merged using **Reciprocal Rank Fusion (RRF)**.

## 3. Database Design

### 3.1 Schema Updates
We will modify the `ddb_knowledge_chunks` table to support automated indexing.

```sql
-- Add a managed token column
ALTER TABLE ddb_knowledge_chunks 
ADD COLUMN content_tokens tsvector 
GENERATED ALWAYS AS (to_tsvector('english', text)) STORED;

-- Add a GIN index for high-performance keyword searching
CREATE INDEX idx_ddb_chunks_content_tokens ON ddb_knowledge_chunks USING GIN(content_tokens);
```

### 3.2 Tokenization Logic (Linguistic vs N-Gram)
As requested, the system will use **Linguistic Tokenization** (Postgres default) rather than character-based N-grams.
- **Stemming**: Uses the `english` dictionary to reduce words to their root (lexemes). e.g., "scaling" and "scaled" both map to `scale`.
- **Stopwords**: Common non-informative words ("the", "is", "a") are removed to keep the index lean and relevant.
- **Phrase Matching**: While it stores single lexemes, it also stores **integer positions**. This allows for exact phrase matching (e.g., "hot partition") by checking if the tokens are adjacent in the original text.
- **Advantage**: Higher precision for technical documentation and lower storage overhead compared to N-grams.

## 4. Java Implementation Plan

### 4.1 Keyword Search Repository
A new `KeywordSearchRepository` will use `JdbcTemplate` to execute native SQL for the keyword portion of the search.

```sql
SELECT embedding_id, text, metadata, 
       ts_rank(content_tokens, plainto_tsquery('english', :query)) as rank
FROM ddb_knowledge_chunks
WHERE content_tokens @@ plainto_tsquery('english', :query)
ORDER BY rank DESC
LIMIT 20;
```

### 4.2 Hybrid Search Service
A new orchestrator service will:
1. Trigger Vector search (via existing `EmbeddingStore`).
2. Trigger Keyword search (via new repository).
3. **Reciprocal Rank Fusion (RRF)**:
   - For each document `d` in the top results of both lists:
   - `Score(d) = Σ (1 / (k + rank(d, search_type)))`
   - Constant `k` is typically set to `60`.
4. Return the top N merged results.

## 5. Success Criteria
- Ingestion remains automated (handled by Postgres Generated Column).
- Queries for exact technical terms (e.g., "BatchWriteItem") return relevant chunks even if the semantic embedding is slightly off.
- The system maintains low latency by performing both searches in parallel (where possible) or efficient sequential execution within the same DB.

## 6. Testing Strategy
- **Unit Test**: Verify the RRF algorithm correctly merges mock ranked lists.
- **Integration Test**: Verify that `to_tsvector` is correctly generating tokens for DDB documentation chunks.
- **E2E Test**: Compare retrieval results for a specific technical keyword before and after the change.
