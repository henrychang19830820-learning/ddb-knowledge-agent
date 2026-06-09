# Design Specification: Hybrid Entity-Match Guardrail for Semantic Cache

**Date:** 2026-06-08  
**Status:** Approved  
**Topic:** Preventing Semantic Drift in LLM Knowledge Agent Cache

## 1. Problem Statement
The current semantic cache uses vector similarity (Cosine Similarity >= 0.92) to intercept repeat questions. However, DynamoDB technical queries are prone to "Semantic Drift" where queries are mathematically similar but technically distinct (e.g., "How to update a GSI?" vs "How to update an LSI?"). This leads to incorrect answers being served from the cache.

## 2. Proposed Solution: Hybrid Entity-Match Guardrail
A two-tier validation system that ensures technical precision before serving a cached response.

### Tier 1: Deterministic Keyword Matching (Regex)
- **Mechanism:** Scans queries for a set of ~100 DynamoDB-specific keywords (APIs, Index types, Limits).
- **Behavior:** If keywords in the new query and cached query match exactly, the cache hit is accepted immediately.
- **Latency:** < 1ms.

### Tier 2: Semantic Equivalence Verification (LLM)
- **Mechanism:** Uses `gemini-3.1-flash-lite` to compare the two queries if the Regex check is ambiguous or mismatched.
- **Behavior:** The LLM determines if the technical intent is identical despite different phrasing (e.g., "partition key" vs "PK").
- **Latency:** ~300-500ms.

## 3. Architecture & Components

### 3.1 Components
- `EntityMatcher`: Java utility for regex-based keyword extraction.
- `EntityVerifier`: LangChain4j service for LLM-based comparison.
- `EntityGuardrailService`: Orchestrator that decides between Tier 1 and Tier 2.
- `QueryRoutingService`: Updated to intercept cache hits and perform guardrail checks.

### 3.2 Metadata Schema Updates
Cache entries in `ddb_knowledge_chunks` will now include:
- `detected_entities`: Array of keywords found at ingestion.
- `original_query`: The original text for LLM comparison.

## 4. Implementation Plan
1. Create `EntityMatcher` with core DynamoDB keyword patterns.
2. Implement `EntityVerifier` using `gemini-3.1-flash-lite`.
3. Create `EntityGuardrailService` to link both.
4. Refactor `QueryRoutingService.askStreaming`:
    - Perform guardrail check on cache hit.
    - Extract and store entities on cache miss (when saving new result).
5. Create integration test to verify drift prevention.

## 5. Success Criteria
- **Zero Drift:** Queries about different technical entities (e.g., GSI vs LSI) never return each other's cached results.
- **Synonym Support:** Phrasing differences (e.g., "DeleteTable" vs "deleting a table") are correctly identified as equivalent.
- **Performance:** Cache hits for exact keyword matches remain < 10ms.
