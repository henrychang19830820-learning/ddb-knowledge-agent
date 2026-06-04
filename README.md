# DDB Knowledge Agent

A local-first, **Hybrid RAG** (Retrieval-Augmented Generation) technical assistant for Amazon DynamoDB. It features a dual-layer architecture: a high-speed **Semantic Cache** and a **Hybrid Retrieval** engine combining Vector Search (`pgvector`) and Keyword Search (Postgres FTS) via **Reciprocal Rank Fusion (RRF)**.

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

Once started, the application will log access URLs:
* **Local UI:** [http://localhost:8090](http://localhost:8090)
* **PGWeb UI:** [http://localhost:5433](http://localhost:5433)

## 🧪 Testing the Agent

### Trigger Ingestion
Before asking questions, populate the knowledge base with the local documentation:
```bash
curl -X POST "http://localhost:8090/ingest"
```

### Ask a Technical Question (Hybrid RAG)
The agent will use both semantic understanding and keyword matching to find the best documentation:
```bash
curl "http://localhost:8090/ask?question=How+to+handle+hot+partitions+in+DynamoDB?"
```

### Verify Semantic Cache
Ask a similar question; if the similarity score is > 0.92, it hits the cache (< 10ms):
```bash
curl "http://localhost:8090/ask?question=What+should+I+do+with+hot+partitions?"
```

## 🛠 Hybrid Architecture
* **Semantic Search:** Uses `all-miniLM-L6-v2` embeddings and HNSW indexes in `pgvector`.
* **Keyword Search:** Uses Postgres native Full-Text Search with linguistic stemming.
* **Fusion (RRF):** Results are merged using the Reciprocal Rank Fusion algorithm to prioritize documents that rank highly in both search types.
* **Semantic Cache:** Intercepts redundant queries to save LLM tokens and reduce latency.

## 📊 Database Management
Use **pgweb** at [http://localhost:5433](http://localhost:5433) to inspect:
* `ddb_knowledge_chunks`: Stores documentation with an automated `tsvector` column.
* `ddb_semantic_cache`: Stores Q&A pairs for fast lookup.
