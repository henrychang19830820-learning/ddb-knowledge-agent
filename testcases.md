# Test Cases: ddb-knowledge-agent

This document outlines the core functional test cases for the `ddb-knowledge-agent`, specifically focusing on **Dynamic Model Routing** and the **Hybrid Entity-Match Guardrail** for semantic caching.

---

## 1. Dynamic Complexity Routing
The agent evaluates the complexity of a user query and routes it to the appropriate model tier (Lite, Flash, or Flash-Lite).

### 1.1 Simple Query (Tier 1)
- **Input:** "What is a DynamoDB table?"
- **Expected Score:** 1 - 3
- **Model Used:** `gemini-3.1-flash-lite` (Simple Tier)
- **Logs:** `Query complexity score: [1-3] for query: 'What is a DynamoDB table?'`
- **Audit DB:** `request_audit_logs` record created with `complexity_score` < 4.

### 1.2 Medium Query (Tier 2)
- **Input:** "How do I use Query with a FilterExpression?"
- **Expected Score:** 4 - 6
- **Model Used:** `gemini-2.5-flash` (Medium Tier)
- **Logs:** `Selected model gemini-2.5-flash [traceId=...] with score [4-6]`
- **Audit DB:** `complexity_score` between 4 and 6.

### 1.3 Complex Query (Tier 3)
- **Input:** "Explain single-table design for a multi-tenant SaaS application with GSIs."
- **Expected Score:** 7 - 10
- **Model Used:** `gemini-3.5-flash` (Complex Tier)
- **Logs:** `Selected model gemini-3.5-flash [traceId=...] with score [7-10]`
- **Audit DB:** `complexity_score` >= 7.

---

## 2. Semantic Caching & Guardrails
The semantic cache intercepts similar questions to reduce latency. The **Hybrid Guardrail** ensures technical precision.

### 2.1 Exact Match (Passthrough)
- **Step 1:** Ask "What is a GSI?" (Result: Cache Miss, answer generated and stored).
- **Step 2:** Ask "What is a GSI?"
- **Expected Behavior:** Instant response from `PgVectorEmbeddingStore`.
- **Logs:** `CACHE_HIT_VALIDATED [traceId=...]: Score 1.0`
- **DB:** `ddb_semantic_cache` contains 1 entry; `request_audit_logs` shows `is_cache_hit = true`.

### 2.2 Entity Match: Regex Tier (Fast Validation)
- **Input 1:** "How do I use the **Query** API?" (Store in cache).
- **Input 2:** "Show me how to perform a **Query**."
- **Expected Behavior:** Vector similarity is high (>0.92). Entity Matcher extracts `QUERY` from both.
- **Logs:** `Guardrail: Exact entity match found: [QUERY]`
- **Output:** Cached answer returned in < 10ms.

### 2.3 Entity Match: LLM Tier (Synonym Validation)
- **Input 1:** "What is a **Partition Key**?" (Store in cache).
- **Input 2:** "What is a **PK**?"
- **Expected Behavior:** 
    - Vector similarity is high.
    - Regex Matcher: `PARTITION KEY` vs `PK` (Mismatch).
    - LLM Fallback: `EntityVerifier` confirms equivalence.
- **Logs:** 
    - `Guardrail: Entity mismatch ([PK] vs [PARTITION KEY]), falling back to LLM`
    - `Guardrail: LLM verification result: true`
- **Output:** Cached answer returned.

### 2.4 Semantic Drift Prevention (Reject)
- **Input 1:** "How to update a **GSI**?" (Store in cache).
- **Input 2:** "How to update an **LSI**?"
- **Expected Behavior:** 
    - Vector similarity is high (0.94+).
    - Regex Matcher: `GSI` vs `LSI` (Mismatch).
    - LLM Fallback: `EntityVerifier` rejects equivalence.
- **Logs:** 
    - `Guardrail: LLM verification result: false`
    - `CACHE_HIT_REJECTED [traceId=...]: Semantic drift detected via guardrail`
- **Audit DB:** `is_cache_hit = false` for the second query.
