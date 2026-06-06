# Hybrid RAG Database Schema Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update PostgreSQL database schema to support Hybrid RAG (Vector + Keyword search) by adding a generated `tsvector` column and a GIN index.

**Architecture:** Add a generated column `content_tokens` to `ddb_knowledge_chunks` table that automatically tokenizes the `text` column for full-text search. Add a GIN index for performance.

**Tech Stack:** PostgreSQL (pgvector image), SQL.

---

### Task 1: Update schema.sql

**Files:**
- Modify: `src/main/resources/schema.sql`

- [ ] **Step 1: Update the ddb_knowledge_chunks table definition in schema.sql**

Modify `src/main/resources/schema.sql` to include the `content_tokens` column and the GIN index.

```sql
-- RAG Knowledge Store
DROP TABLE IF EXISTS ddb_knowledge_chunks;
CREATE TABLE ddb_knowledge_chunks (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    embedding VECTOR(384),
    text TEXT,
    metadata JSONB,
    content_tokens tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED
);

-- HNSW Index for vector search
CREATE INDEX IF NOT EXISTS ddb_knowledge_chunks_embedding_idx 
ON ddb_knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- GIN Index for fast filtering on ANY metadata field
CREATE INDEX IF NOT EXISTS ddb_knowledge_chunks_metadata_idx 
ON ddb_knowledge_chunks USING gin (metadata);

-- GIN Index for fast full-text search
CREATE INDEX IF NOT EXISTS ddb_knowledge_chunks_content_tokens_idx 
ON ddb_knowledge_chunks USING GIN(content_tokens);
```

- [ ] **Step 2: Commit schema.sql changes**

```bash
git add src/main/resources/schema.sql
git commit -m "feat: add FTS generated column and GIN index to schema"
```

### Task 2: Apply changes to running database

**Files:**
- Database: `ddb_agent`

- [ ] **Step 1: Add the content_tokens column to the running database**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "ALTER TABLE ddb_knowledge_chunks ADD COLUMN content_tokens tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED;"`

- [ ] **Step 2: Add the GIN index to the running database**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "CREATE INDEX IF NOT EXISTS ddb_knowledge_chunks_content_tokens_idx ON ddb_knowledge_chunks USING GIN(content_tokens);"`

- [ ] **Step 3: Verify the changes**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "\d ddb_knowledge_chunks"`
Expected: `content_tokens` column exists and `ddb_knowledge_chunks_content_tokens_idx` index exists.
