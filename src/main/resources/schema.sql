-- Idempotent schema: safe to run on every app start (spring.sql.init.mode: always).
-- Existing tables and their data are preserved. To evolve the schema without losing
-- data, add `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` statements below rather
-- than editing a CREATE. To rebuild from scratch (wipes data), run `docker-compose
-- down -v` to drop the Postgres volume, then start fresh.

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- RAG Knowledge Store
CREATE TABLE IF NOT EXISTS ddb_knowledge_chunks (
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

-- Semantic Cache Store
CREATE TABLE IF NOT EXISTS ddb_semantic_cache (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_query TEXT,
    detected_entities TEXT,
    text TEXT,
    embedding VECTOR(384),
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- HNSW Index for semantic cache search
CREATE INDEX IF NOT EXISTS ddb_semantic_cache_embedding_idx 
ON ddb_semantic_cache USING hnsw (embedding vector_cosine_ops);

-- Audit Logs for Request tracking
CREATE TABLE IF NOT EXISTS request_audit_logs (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id UUID,
    query_text TEXT,
    full_prompt TEXT,
    model_name TEXT,
    
    -- Token Metrics
    input_tokens INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    total_tokens INTEGER DEFAULT 0,
    
    -- Cost Metrics (USD)
    input_cost NUMERIC(18, 10) DEFAULT 0,
    output_cost NUMERIC(18, 10) DEFAULT 0,
    total_cost NUMERIC(18, 10) DEFAULT 0,
    
    -- Latency Metrics (milliseconds)
    ttft_ms BIGINT,
    total_latency_ms BIGINT,
    
    -- Metadata
    is_cache_hit BOOLEAN DEFAULT FALSE,
    complexity_score INTEGER,
    tool_calls JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_created_at ON request_audit_logs(created_at);
