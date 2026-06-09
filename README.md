# DDB Knowledge Agent

A local-first, **3-Tier Hybrid ReAct Agent** for Amazon DynamoDB. It features a high-speed **Semantic Cache** with **Entity-Match Guardrails**, an intelligent **Model Router**, and a **ReAct Reasoning Loop** that decides when to search official documentation via **Hybrid Search (Vector + FTS)**.

## 🏗 System Architecture

```text
┌─────────────────┐      ┌──────────────────────────────────────────────────────────┐      ┌───────────────────────────┐
│   Browser UI    │ <──> │                 Spring Boot Application                  │ <──> │      Gemini AI Models     │
└─────────────────┘      │                                                          │      │ (Google AI Studio)        │
                         │  ┌──────────────────┐      ┌──────────────────────────┐  │      │                           │
                         │  │ QueryController  │ <──> │   QueryRoutingService    │ <────> │ 1. Classifier (3.1 Lite)  │
                         │  └──────────────────┘      └────────────┬─────────────┘  │      │ 2. Simple     (3.1 Lite)  │
                                   ^                               │                │      │ 3. Medium     (2.5 Flash) │
                                   │                               v                │      │ 4. Complex    (3.5 Flash) │
                         │  ┌──────┴───────────┐      ┌────────────┴─────────────┐  │      └───────────────────────────┘
                         │  │ IngestionService │      │   ModelRoutingService    │  │
                         │  └──────┬───────────┘      └────────────┬─────────────┘  │
                                   │                               │                │
                         │  ┌──────v───────────┐      ┌────────────v─────────────┐  │      ┌───────────────────────────┐
                         │  │  EmbeddingModel  │      │    DocumentationTool     │  │ <──> │   PostgreSQL + pgvector   │
                         │  └──────┬───────────┘      └────────────┬─────────────┘  │      │                           │
                                   │                               │                │      │ 1. ddb_knowledge_chunks   │
                         │  ┌──────v───────────┐      ┌────────────v─────────────┐  │      │ 2. ddb_semantic_cache     │
                         │  │   AuditService   │ <──> │   EntityGuardrailService │  │ <──> │ 3. request_audit_logs     │
                         │  └──────────────────┘      └──────────────────────────┘  │      └───────────────────────────┘
                         └──────────────────────────────────────────────────────────┘
```

## 🔄 Request Lifecycle

```text
       USER QUESTION
             │
             v
   ┌───────────────────┐
   │  Semantic Cache   │ Search pgvector (Threshold 0.92)
   │      Check        │
   └─────────┬─────────┘
             │ YES (Similarity Hit)
             v
   ┌───────────────────┐         NO        ┌───────────────────┐
   │ Hybrid Guardrail  ├──────────────────>│   Proceed to      │
   │ (Regex + LLM)     │                   │   Model Routing   │
   └─────────┬─────────┘                   └───────────────────┘
             │ YES (Validated)
             v
   ┌───────────────────┐
   │  Return Answer    │ (Latency <10ms for Exact Match)
   │   (Validated)     │
   └───────────────────┘
             │ NO (Cache Miss / Drift Rejected)
             v
   ┌───────────────────┐
   │ Dynamic Routing   │ Evaluate Complexity (1-10)
   │ (Gemini 3.1 Lite) │ via ModelRoutingService
   └─────────┬─────────┘
             │
             v
   ┌───────────────────┐
   │    ReAct Loop     │ 1. Reason: "Do I need documentation?"
   │  (AiServices)     │ 2. Act: Call searchDocumentation()
   │  [Max 10 turns]   │ 3. Observe: Process Hybrid Search results
   └─────────┬─────────┘
             │
             v
   ┌───────────────────┐
   │  Generate Answer  │ Formatted with mandatory 📚 and 🧠 
   │ (Selected Tier)   │ headers for source attribution.
   └─────────┬─────────┘
             │
             v
   ┌───────────────────┐
   │ Distributed Audit │ Link Routing + Generation logs 
   │  & Cache Update   │ via Trace ID in PostgreSQL.
   └───────────────────┘
```

## 🚀 Getting Started

### Prerequisites
* **Java 21** (Amazon Corretto 21 recommended)
* **Docker & Docker Compose**
* **Google Gemini API Key** (Obtain from [Google AI Studio](https://aistudio.google.com/))

### 1. Start Infrastructure
Run the following command to start PostgreSQL with `pgvector` and the `pgweb` UI:

```bash
docker-compose up -d
```

To stop the infrastructure:

```bash
docker-compose down
```

* **Database:** `localhost:5432` (User: `user`, Password: `password`, DB: `ddb_agent`)
* **Database UI:** [http://localhost:5433](http://localhost:5433)

### 2. Configure Environment
Export your Gemini API Key:

```bash
export GOOGLE_API_KEY=your_gemini_api_key_here
```

### 3. Run the Application
Start the technical assistant. Documentation ingestion from the `local_test_docs/` folder can be triggered manually via API.

```bash
./gradlew bootRun
```

Access the UI at: [http://localhost:8090](http://localhost:8090)

## 🧪 Testing the Agent

### Trigger Ingestion
Before asking questions, populate the knowledge base:
```bash
curl -X POST "http://localhost:8090/ingest"
```

### Ask a Technical Question (Streaming)
The agent uses a ReAct loop to decide if it needs to search documentation:
```bash
curl -N "http://localhost:8090/ask-stream?question=How+to+setup+a+GSI%3F"
```

### Verify Semantic Cache
Ask the same question again; it will hit the cache (< 10ms):
```bash
curl -N "http://localhost:8090/ask-stream?question=How+to+setup+a+GSI%3F"
```

### Advanced Test Cases
For detailed testing of Model Routing and Guardrails, see [testcases.md](testcases.md).

## 🛠 Hybrid ReAct Architecture
The agent leverages LangChain4j `AiServices` to provide an autonomous reasoning loop.

1.  **Semantic Cache + Hybrid Guardrail**: Intercepts queries with >0.92 similarity.
    *   **Tier 1 (Regex)**: Instant verification of DynamoDB entities (`GSI`, `PutItem`, etc.).
    *   **Tier 2 (LLM)**: Fallback to verify synonym equivalence (e.g., "PK" vs "Partition Key") using `gemini-3.1-flash-lite`.
2.  **Model Routing**: Evaluates query complexity (1-10) and routes to the optimal tier:
    *   **Simple (1-3)**: `gemini-3.1-flash-lite` (Fast/Cheap)
    *   **Medium (4-6)**: `gemini-2.5-flash`
    *   **Complex (7-10)**: `gemini-3.5-flash` (Highest Reasoning)
3.  **Documentation Tool**: A native tool that performs **Hybrid Search** (Vector + Postgres Full-Text Search) merged via **RRF (Reciprocal Rank Fusion)**.
4.  **Source Attribution**: The agent is strictly instructed to separate information from the documentation vs. its own training data using Markdown headers.

## 📊 Database Management & Auditing
Use **pgweb** at [http://localhost:5433](http://localhost:5433) to inspect:
* `ddb_knowledge_chunks`: Stores vectorized documentation with `tsvector` support.
* `ddb_semantic_cache`: Stores previously generated high-quality answers with `detected_entities`.
* `request_audit_logs`: Detailed tracking of every model call, including:
    * `trace_id`: Links multiple model calls (Routing + Generation) in a single request.
    * `complexity_score`: The 1-10 score that determined the model tier.
    * `full_prompt`: The **actual** structural payload sent to the model (captured via `ChatModelListener`).
    * `tool_calls`: JSON array of every documentation search performed (query + results).
    * `total_cost`: Accurate USD cost calculation based on latest Gemini rates.
    * `ttft_ms`: **Real physical Time To First Token** arrival.
