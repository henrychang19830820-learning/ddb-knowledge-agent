# Source Trace Guide

A reading order for understanding the **DDB Knowledge Agent** architecture by tracing the code in the order a request actually flows through the system.

> Keep the two ASCII diagrams in [`README.md`](../README.md) (System Architecture + Request Lifecycle) open as your map while you read.

## How to use this guide

Read top-to-bottom. Each phase builds on the previous. Every file below is linked, with the **key lines** worth focusing on (line numbers are clickable on GitHub via the `#Lxx` anchors). After each service in Phase 2–3, glance at its matching test under `src/test/...` — the tests are executable documentation with concrete inputs.

**If you only have 10 minutes**, read just the four files in [The happy-path spine](#the-happy-path-spine).

---

## Phase 0 — Orient

Build a mental model of the system and its data before touching logic.

### 1. [`README.md`](../README.md)
The two ASCII diagrams are your map — System Architecture and Request Lifecycle.

### 2. [`high-level-design-and-tech-spec.md`](../high-level-design-and-tech-spec.md)
Design rationale behind the diagrams.

### 3. [`src/main/resources/application.yml`](../src/main/resources/application.yml)
All the knobs in one place.
- [L7-11](../src/main/resources/application.yml#L7-L11) — `spring.config.import: optional:file:.env[.properties]` loads the gitignored `.env` as a property source, so `GOOGLE_API_KEY` (and any secret) is picked up automatically without `export`. `optional:` means a missing file is fine — OS env vars still work. See [`.env.example`](../.env.example) for the format.
- [L34](../src/main/resources/application.yml#L34) — `api-key: ${GOOGLE_API_KEY}` — the placeholder resolved by either the `.env` file above or an OS env var.
- [L37-43](../src/main/resources/application.yml#L37-L43) — the 4 model tiers: classifier + simple (`3.1-flash-lite`), medium (`2.5-flash`), complex (`3.5-flash`), and the routing thresholds (3 / 6).
- [L44-46](../src/main/resources/application.yml#L44-L46) — semantic cache threshold `0.92`.
- [L49-70](../src/main/resources/application.yml#L49-L70) — per-model USD pricing (consumed by `PricingConfig` / `AuditService`).

### 4. [`src/main/resources/schema.sql`](../src/main/resources/schema.sql)
The data model anchors everything else — 3 tables.
- [L5-12](../src/main/resources/schema.sql#L5-L12) — `ddb_knowledge_chunks`: `VECTOR(384)` + a **generated `tsvector` column** for full-text search.
- [L14-24](../src/main/resources/schema.sql#L14-L24) — HNSW (vector) + GIN (FTS) indexes — the hybrid-search foundation.
- [L28-36](../src/main/resources/schema.sql#L28-L36) — `ddb_semantic_cache`: note `original_query` and `detected_entities` columns (the guardrail reads these).
- [L44-70](../src/main/resources/schema.sql#L44-L70) — `request_audit_logs`: `trace_id`, `complexity_score`, `tool_calls` (jsonb), cost & latency metrics.

## Phase 1 — Wiring & entry point

How the app boots and how beans are wired.

### 5. [`DdbKnowledgeAgentApplication.java`](../src/main/java/org/ai/agent/ddbknowledge/DdbKnowledgeAgentApplication.java)
Trivial Spring Boot `main` — 30 seconds.
- [L18-22](../src/main/java/org/ai/agent/ddbknowledge/DdbKnowledgeAgentApplication.java#L18-L22) — `@EnableAsync` (audit writes run async) and `@EnableConfigurationProperties(PricingConfig.class)`.

### 6. [`config/AgentConfig.java`](../src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java)
**Most important wiring file.** Read every bean.
- [L37-75](../src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java#L37-L75) — the 4 Gemini model beans, each registered with a `ChatModelAuditListener`. Note `classifierModel` is a **blocking** `ChatLanguageModel`; the 3 tiers are **streaming**.
- [L78-80](../src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java#L78-L80) — local ONNX `AllMiniLmL6V2EmbeddingModel` (384-dim, runs in-process, no API call).
- [L82-94](../src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java#L82-L94) — `knowledgeStore` (`@Primary`) → `ddb_knowledge_chunks`.
- [L96-114](../src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java#L96-L114) — `cacheStore` → `ddb_semantic_cache`, with `detected_entities` stored as a dedicated column.

### 7. [`web/QueryController.java`](../src/main/java/org/ai/agent/ddbknowledge/web/QueryController.java)
The HTTP surface — it just delegates.
- [L66-118](../src/main/java/org/ai/agent/ddbknowledge/web/QueryController.java#L66-L118) — `/ask-stream`: builds an `SseEmitter` and wires `onNext`/`onComplete`/`onError` to SSE events. **This is where a request enters.**
- [L30-40](../src/main/java/org/ai/agent/ddbknowledge/web/QueryController.java#L30-L40) — `/ingest`; [L42-64](../src/main/java/org/ai/agent/ddbknowledge/web/QueryController.java#L42-L64) — `/docs` (clear KB) and `/cache` (clear cache).

## Phase 2 — The request pipeline (the heart)

Trace one `/ask-stream` request through its collaborators, in call order.

### 8. [`service/QueryRoutingService.java`](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java)
**The central orchestrator.** Read `askStreaming()` slowly — it sequences the entire pipeline.
- [L89-98](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java#L89-L98) — generates a `traceId`, spawns a **background thread** (so the SSE emitter returns immediately), and seeds the `AuditContext`.
- [L102-110](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java#L102-L110) — **Step 1+2: semantic cache check** — embed query, search `cacheStore` with `minScore = 0.92`.
- [L112-140](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java#L112-L140) — **cache hit path**: calls `entityGuardrailService.isValid(...)`; if validated, returns cached answer (`CACHE_HIT`, ~0 tokens) and **returns early**; else logs `CACHE_HIT_REJECTED` and falls through.
- [L145-161](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java#L145-L161) — **Step 4: model selection** — `getComplexityScore()` → pick simple / medium / complex tier by threshold.
- [L162-183](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java#L162-L183) — the **system prompt** (mandatory 📚 / 🧠 source-attribution format) + the LangChain4j `AiServices` ReAct agent build (chat memory + `documentationTool`).
- [L190-200](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java#L190-L200) — **Step 5: native `TokenStream`** — streams tokens to the handler, captures real TTFT on the first token.
- [L201-240](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java#L201-L240) — **`onComplete`**: serialize tool calls, write the audit record, and **update the semantic cache** with the new answer + extracted entities.

### 9. [`service/EntityGuardrailService.java`](../src/main/java/org/ai/agent/ddbknowledge/service/EntityGuardrailService.java)
Two-tier cache validation — prevents semantic-drift false hits.
- [L17-25](../src/main/java/org/ai/agent/ddbknowledge/service/EntityGuardrailService.java#L17-L25) — **Tier 1**: extract entities from both queries; exact set match → valid (instant).
- [L27-31](../src/main/java/org/ai/agent/ddbknowledge/service/EntityGuardrailService.java#L27-L31) — **Tier 2**: on mismatch, fall back to the LLM verifier.

#### 9a. [`guardrail/EntityMatcher.java`](../src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityMatcher.java)
- [L11-14](../src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityMatcher.java#L11-L14) — the big case-insensitive regex of DynamoDB entities (`GSI`, `LSI`, `PutItem`, `Partition Key`, …).
- [L16-24](../src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityMatcher.java#L16-L24) — `extractEntities()` returns an upper-cased `Set` (used both here and when writing the cache in step 8).

#### 9b. [`guardrail/EntityVerifier.java`](../src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityVerifier.java)
- [L15-30](../src/main/java/org/ai/agent/ddbknowledge/guardrail/EntityVerifier.java#L15-L30) — prompt asks the **classifier model** to answer only `EQUIVALENT` / `DIFFERENT` (e.g. "PK" vs "Partition Key" → equivalent; GSI vs LSI → different).

### 10. [`service/ModelRoutingService.java`](../src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java)
Complexity scoring → tier selection.
- [L34-52](../src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java#L34-L52) — prompt asks for an integer 1–10; parses it defensively (strips non-digits).
- [L56-66](../src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java#L56-L66) — records its **own** audit row under the same `traceId` (this is how routing + generation get linked).
- [L69-82](../src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java#L69-L82) — on error, **defaults to score 10** (safest = route to complex tier).

### 11. [`tool/DocumentationTool.java`](../src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java)
The native ReAct tool the agent decides to call.
- [L21-22](../src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java#L21-L22) — the `@Tool` annotation + description; this text is what the LLM reads to decide when to call it.
- [L24](../src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java#L24) — delegates to `hybridSearchService.search(query, 5)`.
- [L37-40](../src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java#L37-L40) — records the tool execution into `AuditContext` (high-fidelity `tool_calls` logging).

### 12. [`service/HybridSearchService.java`](../src/main/java/org/ai/agent/ddbknowledge/service/HybridSearchService.java)
Vector + FTS merged via Reciprocal Rank Fusion.
- [L32-38](../src/main/java/org/ai/agent/ddbknowledge/service/HybridSearchService.java#L32-L38) — vector search over `knowledgeStore` (fetches `maxResults * 2`).
- [L40-41](../src/main/java/org/ai/agent/ddbknowledge/service/HybridSearchService.java#L40-L41) — keyword search via the repository.
- [L43-61](../src/main/java/org/ai/agent/ddbknowledge/service/HybridSearchService.java#L43-L61) — **RRF fusion**: `score(d) = Σ 1 / (60 + rank)` across both result lists, keyed by `embeddingId`.
- [L63-76](../src/main/java/org/ai/agent/ddbknowledge/service/HybridSearchService.java#L63-L76) — sort by fused score, take top `maxResults`.

#### 12a. [`repository/KeywordSearchRepository.java`](../src/main/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepository.java)
- [L29-36](../src/main/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepository.java#L29-L36) — the FTS SQL: `ts_rank(content_tokens, plainto_tsquery('english', ?))` against `ddb_knowledge_chunks`.
- [L38-64](../src/main/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepository.java#L38-L64) — maps rows back into LangChain4j `EmbeddingMatch<TextSegment>` (no vector — keyword results don't carry one).

## Phase 3 — Supporting subsystems

Orthogonal to the main query path — read once the spine makes sense.

### 13. [`service/IngestionService.java`](../src/main/java/org/ai/agent/ddbknowledge/service/IngestionService.java)
How `local_test_docs/` becomes searchable chunks (the offline/setup path).
- [L39-48](../src/main/java/org/ai/agent/ddbknowledge/service/IngestionService.java#L39-L48) — load `*.md`, inject `chunking_strategy` + `timestamp` metadata.
- [L57-63](../src/main/java/org/ai/agent/ddbknowledge/service/IngestionService.java#L57-L63) — `EmbeddingStoreIngestor` with **recursive splitter (1000 chars / 200 overlap)** → embeds → writes to `knowledgeStore`.

### 14. Audit subsystem (cross-cutting observability)
Read in this order — it's how trace IDs, cost, and TTFT are captured without polluting the main logic.

#### 14. [`audit/ChatModelAuditListener.java`](../src/main/java/org/ai/agent/ddbknowledge/audit/ChatModelAuditListener.java)
- [L17-27](../src/main/java/org/ai/agent/ddbknowledge/audit/ChatModelAuditListener.java#L17-L27) — `onRequest` captures the **actual** structural prompt sent to the model (the "high-fidelity" `full_prompt`).

#### 14a. [`audit/AuditContext.java`](../src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java) + [`audit/AuditContextHolder.java`](../src/main/java/org/ai/agent/ddbknowledge/audit/AuditContextHolder.java)
- [AuditContext L18-25](../src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java#L18-L25) — accumulates `toolExecutions` per request.
- [AuditContextHolder L4](../src/main/java/org/ai/agent/ddbknowledge/audit/AuditContextHolder.java#L4) — a `ThreadLocal<AuditContext>` — note this ties into the background-thread design in step 8.

#### 14b. [`service/AuditService.java`](../src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java)
- [L22-23](../src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java#L22-L23) — `@Async` write (doesn't block the response).
- [L37-60](../src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java#L37-L60) — the `INSERT` into `request_audit_logs` (`trace_id` as UUID, `tool_calls` as `::jsonb`).
- [L63-92](../src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java#L63-L92) — model-name → price lookup with exact then **best-prefix** matching.

#### 14c. [`dto/AuditRecord.java`](../src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java)
- [L8-20](../src/main/java/org/ai/agent/ddbknowledge/dto/AuditRecord.java#L8-L20) — the full field set of one audit row (the builder you saw used in steps 8 & 10).

#### 14d. [`config/PricingConfig.java`](../src/main/java/org/ai/agent/ddbknowledge/config/PricingConfig.java)
- [L13-25](../src/main/java/org/ai/agent/ddbknowledge/config/PricingConfig.java#L13-L25) — binds `agent.pricing.*` from `application.yml`; `getModels()` aliases the map for `AuditService`.

## Phase 4 — Confirm via tests

The `src/test/...` files are executable documentation. After each service, glance at its matching test:

- [`service/HybridSearchServiceTest.java`](../src/test/java/org/ai/agent/ddbknowledge/service/HybridSearchServiceTest.java)
- [`service/ModelRoutingServiceTest.java`](../src/test/java/org/ai/agent/ddbknowledge/service/ModelRoutingServiceTest.java)
- [`service/AuditServiceTest.java`](../src/test/java/org/ai/agent/ddbknowledge/service/AuditServiceTest.java)
- [`service/AiServicesTest.java`](../src/test/java/org/ai/agent/ddbknowledge/service/AiServicesTest.java)
- [`guardrail/EntityGuardrailTest.java`](../src/test/java/org/ai/agent/ddbknowledge/guardrail/EntityGuardrailTest.java)
- [`guardrail/EntityVerifierTest.java`](../src/test/java/org/ai/agent/ddbknowledge/guardrail/EntityVerifierTest.java)
- [`repository/KeywordSearchRepositoryTest.java`](../src/test/java/org/ai/agent/ddbknowledge/repository/KeywordSearchRepositoryTest.java)
- [`tool/DocumentationToolTest.java`](../src/test/java/org/ai/agent/ddbknowledge/tool/DocumentationToolTest.java)

---

## The happy-path spine

If you only read four files, read them in this order — it's the full happy path:

1. [`config/AgentConfig.java`](../src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java) — what exists and how it's wired
2. [`web/QueryController.java`](../src/main/java/org/ai/agent/ddbknowledge/web/QueryController.java) — where a request enters
3. [`service/QueryRoutingService.java`](../src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java) — how a request is orchestrated
4. [`tool/DocumentationTool.java`](../src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java) — how the agent retrieves knowledge

## Request flow at a glance

```text
HTTP GET /ask-stream                          QueryController.askStream()        [L66]
  → QueryRoutingService.askStreaming()  (background thread + traceId)            [L89]
      1. Embed query + semantic cache check   (cacheStore, minScore 0.92)        [L102]
      2. EntityGuardrailService.isValid()                                        [L118]
         → EntityMatcher (regex, Tier 1) → EntityVerifier (LLM, Tier 2)
         ├─ validated  → stream cached answer, return early                      [L134]
         └─ miss/reject ↓
      3. ModelRoutingService.getComplexityScore()  → 1–10 → pick tier            [L145]
      4. AiServices ReAct loop (system prompt + chat memory)                     [L178]
           → DocumentationTool.searchDocumentation()                             [L24]
             → HybridSearchService.search()  (vector + FTS via RRF)              [L31]
               → KeywordSearchRepository  (ts_rank / plainto_tsquery)            [L28]
      5. Stream answer tokens (SSE), capture real TTFT                           [L192]
      6. onComplete: AuditService.recordAudit() + update semantic cache          [L220]

  Cross-cutting: ChatModelAuditListener.onRequest() captures the real prompt;
                 every model call writes a row to request_audit_logs sharing the
                 same trace_id (routing row + generation row).
```
