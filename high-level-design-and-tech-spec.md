# High-Level Design & Technical Specification: `ddb-knowledge-agent`

## 0. Prerequisites
Before starting development, ensure the following tools are installed in your local environment:

*   **Java:** Amazon Corretto 21.0.3 (or later).
*   **Docker & Docker Compose:** For running PostgreSQL + pgvector and PGWeb.
*   **Google Gemini API Key:** Required for the generation phase.

## 1. Introduction & Objectives
`ddb-knowledge-agent` is a local-first Java Spring Boot intelligent agent designed to answer technical questions precisely using the official Amazon DynamoDB Developer Guide. 

The core architectural challenge is to maximize system performance, minimize latency, and save LLM token costs without sacrificing accuracy. To achieve this, the project implements a triple-layer retrieval strategy: **Semantic Cache Layer**, **Vector Search Layer**, and **Keyword Search Layer** (combined via **Hybrid RAG**).

### Core Objectives
*   **Minimal Latency:** Utilize a local semantic cache to intercept identical or highly similar questions, achieving < 10ms responses without triggering remote LLM calls.
*   **Contextual Accuracy:** Use Hybrid Search (Vector + Keyword) to ensure technical terms (e.g., "GSI", "TTL") and exact phrases are retrieved correctly from Markdown documentation.
*   **Local-First Architecture:** The entire system runs locally using Java 21, Spring Boot 3.x, and PostgreSQL + pgvector for persistence. Generation is powered by Google Gemini Pro via the AI Studio API.

---

## 2. Architectural Overview

The system follows a sequential interceptor and hybrid retrieval pattern:

```text
                  +-----------------------+
                  |    User Question      |
                  +-----------+-----------+
                              |
                              v
                  +-----------+-----------+
                  |   Generate Embedding  |
                  +-----------+-----------+
                              |
                              v
            +-----------------+-----------------+
            |   Query Semantic Cache (pgvector) |
            +-----------------+-----------------+
                              |
                [Cosine Similarity >= 0.92]
                              |
              +---------------+---------------+
              |                               |
        (Cache Hit)                    (Cache Miss)
              |                               |
              v                               v
    +---------+---------+           +---------+---------+
    | Return Cached     |           |   Hybrid Search   |
    | Response          |           | (Vector + Keyword)|
    +---------+---------+           +---------+---------+
                                              |
                                              v
                                    +---------+---------+
                                    | RRF Fusion Sorting|
                                    | (Reciprocal Rank) |
                                    +---------+---------+
                                              |
                                              v
                                    +---------+---------+
                                    | Generate Answer   |
                                    | (Gemini Flash)    |
                                    +---------+---------+
                                              |
                                              v
                                    +---------+---------+
                                    | Update Cache with |
                                    | New Q&A Pair      |
                                    +---------+---------+
                                              |
                                              v
                +-------------+-------------+
                |  Final Answer to User     |
                +---------------------------+
```

### Component Matrix

| Category | Technology | Strategy |
| :--- | :--- | :--- |
| **Core Framework** | Spring Boot 3.3.0 (Java 21) | Lightweight, local-first driver. |
| **Orchestration** | LangChain4j | Manages embeddings, vector store, and Gemini API bindings. |
| **Embedding Engine** | `all-miniLM-L6-v2` (ONNX) | Runs locally via JVM; zero network latency for embeddings. |
| **Vector Store** | PostgreSQL 16 + pgvector | Stores documentation chunks and semantic cache. |
| **Keyword Search** | Postgres Full-Text Search | Uses `tsvector` with linguistic tokenization (stemming). |
| **Fusion Algorithm** | Reciprocal Rank Fusion (RRF) | Merges vector and keyword ranks without manual weighting. |
| **LLM Provider** | Gemini (Google AI Studio) | Preferred model: `gemini-1.5-flash` for speed/cost balance. |

---

## 3. Database Schema

The database is initialized via `schema.sql` and includes specialized indexes for both vector and keyword retrieval.

### 3.1. Knowledge Store (`ddb_knowledge_chunks`)
Stores the vectorized fragments of the technical documentation.

```sql
CREATE TABLE ddb_knowledge_chunks (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    embedding VECTOR(384),
    text TEXT,
    metadata JSONB,
    -- Managed Full-Text Tokens
    content_tokens tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED
);

-- HNSW Index for semantic vector search
CREATE INDEX ON ddb_knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- GIN Index for linguistic keyword search
CREATE INDEX ON ddb_knowledge_chunks USING GIN(content_tokens);
```

### 3.2. Semantic Cache (`ddb_semantic_cache`)
Stores previously generated answers to prevent redundant LLM calls.

```sql
CREATE TABLE ddb_semantic_cache (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_query TEXT,
    text TEXT, -- Stores the cached answer
    embedding VECTOR(384), -- Vector of the original query
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 4. Subsystem Details

### 4.1. Hybrid Retrieval & RRF
To solve the "technical term" problem where embeddings might miss specific keywords like "GSI" or "LSI", the system uses **Reciprocal Rank Fusion (RRF)**:
1.  Perform **Vector Search** (top 10 results).
2.  Perform **Keyword Search** (top 10 results using Postgres FTS).
3.  Assign a score: $Score(d) = \sum \frac{1}{60 + rank(d)}$.
4.  Sort by the combined score and pick the top 5 for the LLM context.

### 4.2. Ingestion Pipeline
*   **Source:** Local Markdown files in the `local_test_docs` directory.
*   **Splitter:** Recursive text splitter (1000 character chunks, 200 character overlap).
*   **Automation:** Postgres generated columns automatically update the keyword index (`tsvector`) whenever Java inserts a document.

---

## 5. Implementation Roadmap

### Phase 1: Infrastructure & DB
*   Docker Compose for PostgreSQL with `pgvector`.
*   Schema initialization with HNSW and GIN indexes.

### Phase 2: Ingestion
*   `IngestionService` for document loading and local embedding generation.

### Phase 3: Hybrid Search
*   `KeywordSearchRepository` for Postgres FTS queries.
*   `HybridSearchService` implementing the RRF fusion logic.

### Phase 4: Routing & Generation
*   `QueryRoutingService` managing the cache-first flow and RAG fallback.
*   Integration with Gemini AI Studio.

---

## 6. Useful PostgreSQL Queries

### 6.1. Perform Keyword Search
```sql
SELECT text, ts_rank(content_tokens, plainto_tsquery('english', 'GSI partitions')) as rank
FROM ddb_knowledge_chunks
WHERE content_tokens @@ plainto_tsquery('english', 'GSI partitions')
ORDER BY rank DESC LIMIT 5;
```

### 6.2. Inspect Metadata and Chunks
```sql
SELECT metadata->>'chunking_strategy' as strategy, LEFT(text, 100) as snippet 
FROM ddb_knowledge_chunks 
ORDER BY (metadata->>'timestamp')::timestamp DESC;
```
