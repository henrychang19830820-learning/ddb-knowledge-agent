# DDB Knowledge Agent

A local-first RAG (Retrieval-Augmented Generation) technical assistant for Amazon DynamoDB, featuring a dual-layer semantic cache and RAG architecture.

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
Export the following variables in your terminal or add them to your `~/.bashrc`:

```bash
export GOOGLE_API_KEY=your_gemini_api_key_here
```

### 3. Run the Application
Start the technical assistant. Documentation ingestion will happen automatically on startup:

```bash
./gradlew bootRun
```

## 🧪 Testing the Agent

### Ask a Technical Question
You can use `curl` or any browser to ask technical questions:

```bash
curl "http://localhost:8090/ask?question=How+to+handle+hot+partitions+in+DynamoDB?"
```

### Verify Semantic Cache
Ask a similar question and check the application logs for `CACHE_HIT`. The second response should be nearly instantaneous (< 10ms).

```bash
curl "http://localhost:8090/ask?question=What+should+I+do+with+hot+partitions?"
```

## 🛠 Project Structure
* `src/main/resources/schema.sql`: Initializes the database with `pgvector` and HNSW indexes.
* `org.ai.agent.ddbknowledge.service.QueryRoutingService`: The brain of the agent, handling cache lookups and RAG fallback.
* `org.ai.agent.ddbknowledge.service.IngestionService`: Handles document splitting and vector ingestion.

## 📊 Database Management
Use **pgweb** at [http://localhost:5433](http://localhost:5433) to inspect the stored chunks and cache:
* `ddb_knowledge_chunks`: Stores the vectorized documentation.
* `ddb_semantic_cache`: Stores previously answered questions.
