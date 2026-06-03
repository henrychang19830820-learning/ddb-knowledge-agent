-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- RAG Knowledge Store
DROP TABLE IF EXISTS ddb_knowledge_chunks;
CREATE TABLE ddb_knowledge_chunks (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    embedding VECTOR(384),
    text TEXT,
    metadata JSONB
);

-- HNSW Index for vector search
CREATE INDEX IF NOT EXISTS ddb_knowledge_chunks_embedding_idx 
ON ddb_knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- GIN Index for fast filtering on ANY metadata field
CREATE INDEX IF NOT EXISTS ddb_knowledge_chunks_metadata_idx 
ON ddb_knowledge_chunks USING gin (metadata);

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
