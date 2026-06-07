# High-Level Design & Technical Specification: `ddb-knowledge-agent`

## 0. Prerequisites
Before starting development, ensure the following tools are installed in your local environment:

*   **Java:** Amazon Corretto 21.0.3 (or later).
*   **Docker & Docker Compose:** For running PostgreSQL + pgvector and PGWeb.
*   **Google Gemini API Key:** Required for the generation phase.

## 1. Introduction & Objectives
`ddb-knowledge-agent` is a local-first Java Spring Boot intelligent agent designed to answer technical questions precisely using the official Amazon DynamoDB Developer Guide. 

The system uses a **ReAct (Reasoning + Acting) Agent** architecture. Instead of hardcoded retrieval, the agent has agency to decide whether to search the documentation tool or rely on its internal training data, ensuring a more natural and comprehensive conversation.

### Core Objectives
*   **Minimal Latency:** Utilize a local semantic cache to intercept identical or highly similar questions, achieving < 10ms responses.
*   **ReAct Architecture:** Implements a Tool-based reasoning loop (max 10 turns) allowing the agent to fetch technical context as needed.
*   **Knowledge Distinction:** The agent explicitly distinguishes between information retrieved from the official documentation versus its own model training data.
*   **Cost-Efficient Routing:** Automatically routes queries to different model tiers (Simple, Medium, Complex) based on an initial complexity evaluation.

---

## 2. Architectural Overview

The system follows a sequential interceptor, dynamic routing, and ReAct loop pattern:

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
    | Return Cached     |           | Dynamic Routing   |
    | Response          |           | (Complexity Score)|
    +---------+---------+           +---------+---------+
                                              |
                                              v
                                    +---------+---------+
                                    |    ReAct Loop     |
                                    |  (max 10 turns)   |
                                    +---------+---------+
                                              |       ^
                                              v       |
                                    +---------+---------+
                                    | Documentation Tool|
                                    | (Vector + Keyword)|
                                    +---------+---------+
                                              |
                                              v
                                    +---------+---------+
                                    | Generate Answer   |
                                    | (Source Citing)   |
                                    +---------+---------+
                                              |
                                              v
                                    +---------+---------+
                                    | Update Cache and  |
                                    | Distributed Audit |
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
| **Orchestration** | LangChain4j `AiServices` | Manages the ReAct loop and Tool execution. |
| **Model Tiers** | Gemini 2.5/3.1/3.5 | Routes to Lite (Simple), Flash-Lite (Medium), or Flash (Complex). |
| **Agent Logic** | ReAct + ChatMemory | Bounded to 10 messages to prevent infinite loops. |
| **Vector Store** | PostgreSQL 16 + pgvector | Stores documentation chunks and semantic cache. |
| **Audit Log** | PostgreSQL + Trace ID | Records tokens, costs, latency, and routing scores across calls. |

---

## 3. Database Schema

### 3.1. Knowledge Store (`ddb_knowledge_chunks`)
Stores the vectorized fragments of the technical documentation.

```sql
CREATE TABLE ddb_knowledge_chunks (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    embedding VECTOR(384),
    text TEXT,
    metadata JSONB,
    content_tokens tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED
);
```

### 3.2. Audit Logs (`request_audit_logs`)
Links multiple LLM calls per request via `trace_id`.

```sql
CREATE TABLE request_audit_logs (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id UUID,
    query_text TEXT,
    model_name TEXT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_cost NUMERIC(18, 10),
    complexity_score INTEGER,
    ttft_ms BIGINT,
    total_latency_ms BIGINT,
    is_cache_hit BOOLEAN,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 4. ReAct Capabilities

### 4.1. Documentation Tool
The agent has access to a `DocumentationTool` that performs a **Hybrid Search** (Vector + Keyword) on the knowledge store.
- **Vector Search**: Captures semantic intent.
- **Keyword Search**: Captures technical exactness (e.g. "GSI", "BatchWriteItem").
- **Fusion**: Merges results via **Reciprocal Rank Fusion (RRF)**.

### 4.2. Reasoning & Citing
The agent is instructed to:
1.  Search the documentation for factual verification.
2.  Synthesize the answer using both the tool output and its own knowledge.
3.  **Explicitly state** which parts of the answer are from documentation and which are from its training data.

---

## 5. Implementation Roadmap

### Phase 1: Infrastructure & DB
*   Postgres + pgvector setup.
*   Audit schema with trace_id and complexity scoring support.

### Phase 2: Hybrid Retrieval
*   Postgres Full-Text Search (FTS) integration.
*   RRF algorithm for merging search results.

### Phase 3: Dynamic Model Routing
*   Complexity classifier using `gemini-2.5-flash-lite`.
*   3-tier model selection strategy.

### Phase 4: ReAct Loop & Tools
*   `DocumentationTool` implementation.
*   Refactor to `AiServices` with 10-turn bounded memory.
*   Source-aware system prompting.
