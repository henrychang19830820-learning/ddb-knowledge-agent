-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

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

-- Semantic Cache Store
DROP TABLE IF EXISTS ddb_semantic_cache;
CREATE TABLE ddb_semantic_cache (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_query TEXT,
    text TEXT,
    embedding VECTOR(384),
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- HNSW Index for semantic cache search
CREATE INDEX IF NOT EXISTS ddb_semantic_cache_embedding_idx 
ON ddb_semantic_cache USING hnsw (embedding vector_cosine_ops);

-- Audit Logs for Request tracking
DROP TABLE IF EXISTS request_audit_logs;
CREATE TABLE request_audit_logs (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_text TEXT,
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_created_at ON request_audit_logs(created_at);
