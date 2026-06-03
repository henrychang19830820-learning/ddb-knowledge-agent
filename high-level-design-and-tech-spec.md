# High-Level Design & Technical Specification: `ddb-knowledge-agent`

## 0. 前置需求 (Prerequisites)
在開始開發前，請確保本地環境已安裝以下工具：

* **SDKMAN!:** 用於管理 Java 版本。
* **Java:** Amazon Corretto 21.0.3-amzn (透過 `sdk install java 21.0.3-amzn` 安裝)。
* **Docker & Docker Compose:** 用於運行 PostgreSQL + pgvector。

## 1. 專案簡介與目標 (Introduction & Objectives)
`ddb-knowledge-agent` 是一個在本地端運行的 Java Spring Boot 智慧代理 (Agent)，旨在利用 Amazon DynamoDB 的官方開發者指南來精準回答技術問題。本專案的核心架構挑戰在於：如何在不犧牲回答正確性的前提下，極大化系統效能、降低延遲並節省 LLM 權限成本。為此，本專案導入了雙層架構設計：**語意快取層 (Semantic Cache Layer)** 與 **檢索增強生成層 (RAG Layer)**。

### 核心目標
* **極致低延遲 (Latency Minimization):** 利用本地向量快取攔截重複或語意高度相似的提問，達成小於 10ms 的直接回應，完全不觸發遠端 LLM。
* **精準上下文 (Contextual Accuracy):** 確保 RAG 層能精確檢索官方 Markdown 文件的文本切塊 (Chunks)，並將其注入至 LLM 提示詞中，徹底杜絕模型幻覺。
* **本地優先架構 (Local-First Architecture):** 整個系統以 Java 21 與 Spring Boot 3.x 於本地端驅動，並採用 **PostgreSQL + pgvector** 進行向量永續化儲存與相似度檢索，生成端則對接 Google AI Studio 的 Gemini Pro API 免費額度。

---

## 2. 系統架覽總覽 (Architectural Overview)

本系統遵循順序攔截器模式 (Sequential Interceptor Pattern)：

```text
                  +-----------------------+
                  |   使用者輸入技術問題  |
                  +-----------+-----------+
                              |
                              v
                  +-----------+-----------+
                  |   生成問題向量 Embedding |
                  +-----------+-----------+
                              |
                              v
            +-----------------+-----------------+
            |   查詢快取資料表 (pgvector)       |
            +-----------------+-----------------+
                              |
                [餘弦距離運算子 (Cosine Distance) <=>]
                              |
              +---------------+---------------+
              |                               |
       (>= 相似度閥值)                 (< 相似度閥值)
              |                               |
              v (Cache Hit 快取命中)           v (Cache Miss 快取未命中)
    +---------+---------+           +---------+---------+
    |  直接從快取資料表 |           |  查詢 RAG 知識庫  |
    |  撈出歷史生成答案 |           |  (pgvector 資料表)|
    +---------+---------+           +---------+---------+
              |                               |
              |                               v
              |                     +---------+---------+
              |                     |  將檢索文本組裝  |
              |                     |  呼叫 Gemini API  |
              |                     +---------+---------+
              |                               |
              |                               v
              |                     +---------+---------+
              |                     |  將新答案與問題寫 |
              |                     |  入快取資料表備用 |
              |                     +---------+---------+
              |                               |
              +---------------+---------------+
                              |
                              v
                +-------------+-------------+
                |  將最終答案回傳給使用者/  |
                |  自動化測試 Harness 腳本  |
                +---------------------------+
```

### 技術棧矩陣 (Components Matrix)

| 組件類型 | 技術選型 | 設計核心與策略 |
| :--- | :--- | :--- |
| **基礎核心框架** | Spring Boot 3.x (Java 21) | 本地端主要驅動平台，保持輕量化。 |
| **建置管理工具** | Gradle | 本地專案建置與依賴管理。 |
| **AI 抽象封裝層** | LangChain4j | 負責編排 Embeddings 計算、向量庫對接、與 Gemini API 的綁定。 |
| **向量生成引擎** | `all-miniLM-L6-v2` (ONNX) | 透過 `langchain4j-embeddings-all-minilm-l6-v2` 完全在本地 JVM 內運行，無外部網路開銷。 |
| **向量資料庫** | PostgreSQL 16 + pgvector | 以 Docker Compose 於本地端建立，同時託管「知識庫」與「語意快取」兩張向量表。 |
| **資料庫驅動** | `langchain4j-pgvector` | LangChain4j 官方提供的 pgvector 原生連接器。 |
| **LLM 供應商** | Gemini (Google AI Studio) | 優先使用 `gemini-3.5-flash` 或 `gemini-2.5-flash` (已驗證可用)。 |

### 3.0. 驗證可用模型清單 (Verified Available Models)
經測試，以下模型在當前 API Key 下為可用狀態，優先推薦 Flash 系列以確保低延遲：

* **推薦首選:** `gemini-3.5-flash` (最新、平衡性最佳)
* **穩定備選:** `gemini-2.5-flash`, `gemini-3-flash-preview`
* **極致效能:** `gemini-2.5-flash-lite`, `gemini-3.1-flash-lite-preview`
* **注意:** Pro 系列 (`gemini-2.5-pro` 等) 目前受限於免費額度配額 (Quota Exceeded)，暫不建議作為預設模型。

---

## 3. 資料庫結構與 DDL 定義 (Data Schema & Contracts)

在 Spring Boot 應用程式啟動前，必須先在目標 PostgreSQL 資料庫中啟用 pgvector 擴充功能 (`CREATE EXTENSION IF NOT EXISTS vector;`)。

### 3.1. RAG 知識庫資料表 (RAG Knowledge Store Schema)
* **資料表名稱:** `ddb_knowledge_chunks`
* **向量維度:** 384 (符合 `all-miniLM-L6-v2` 標準)

```sql
CREATE TABLE ddb_knowledge_chunks (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    embedding VECTOR(384),
    text TEXT,
    metadata JSONB
);

-- HNSW 索引以加速向量餘弦相似度檢索
CREATE INDEX ON ddb_knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- GIN 索引以加速 Metadata 欄位過濾
CREATE INDEX ON ddb_knowledge_chunks USING gin (metadata);

-- 功能性索引以加速 JSONB 內的時間戳記排序
CREATE INDEX ON ddb_knowledge_chunks (((metadata->>'timestamp')::timestamp));
```

### 3.2. 語意快取資料表 (Semantic Cache Store Schema)
* **資料表名稱:** `ddb_semantic_cache`
* **向量維度:** 384

```sql
CREATE TABLE ddb_semantic_cache (
    embedding_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_query TEXT,
    cached_response TEXT,
    embedding VECTOR(384),
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- HNSW 索引以加速快取攔截查詢
CREATE INDEX ON ddb_semantic_cache USING hnsw (embedding vector_cosine_ops);
```

---

## 4. 核心子系統細節 (Subsystem Deep Dive)

### 4.1. 語意快取攔截器 (Semantic Cache Interceptor)
在將新問題的 Embedding 送入 RAG 檢索之前，系統必須先對 `ddb_semantic_cache` 資料表進行相似度比對。

* **相似度分數計算:** 利用 pgvector 的 `<=>` 餘弦距離運算子。相似度得分定義為 `1 - distance`。
* **閥值配置 (Threshold Config):** 於 `application.yml` 配置可調參數 `agent.cache.semantic-threshold` (預設建議值: `0.92`)。
* **LangChain4j 配置策略:** 在 Spring 容器中配置兩個獨立的 `PgVectorStore` Bean 實例，分別綁定 `ddb_semantic_cache` 與 `ddb_knowledge_chunks` 資料表。

### 4.2. 文件切塊規格 (Ingestion Pipeline)
* **資料來源:** 位於 `/home/localdev/projects/amazon-dynamodb-developer-guide/doc_source` 的 Markdown 檔案。
* **切塊器 (Splitter):** 採用 LangChain4j 的 `DocumentSplitters`，配置 Markdown 標題感知的層次化解析，防止程式碼區塊（Code Blocks）或重要架構表格被無情切斷。
* **切塊大小 (Chunk Size):** 限制在 ~1000 字元左右。
* **重疊長度 (Overlap):** 設定 ~200 字元，用以保留跨切塊的邊界語意。

---

## 5. 給 Gemini CLI 的開發實作路線圖 (Development Roadmap)

當把此架構規格書餵給 Gemini CLI 時，請引導其按照以下四個階段推進：

### 階段一：基礎建設與資料庫初始化 (Infrastructure Setup)
* 撰寫 `docker-compose.yml` 配置 `pgvector/pgvector:pg16` 映像檔。
* 在 `build.gradle` 中引入：`langchain4j-core`、`langchain4j-google-ai-studio`、`langchain4j-embeddings-all-minilm-l6-v2`、`langchain4j-pgvector` 以及 `postgresql` 驅動。
* 配置 `application.yml` 的資料庫連線資訊與 Gemini API Key。

### 階段二：知識庫寫入流水線 (Ingestion Pipeline)
* 透過 SQL 腳本或 Flyway 在資料庫初始化時建立 vector 擴充功能與資料表。
* 實作 `IngestionService.java`，掃描 `/home/localdev/projects/amazon-dynamodb-developer-guide/doc_source` 目錄下的所有 `.md` 檔案，執行切塊，並透過 JVM 本地 ONNX 模型計算向量後存入 `ddb_knowledge_chunks` 表中。

### 階段三：雙向量庫路由與生成邏輯 (Routing & Generation Engine)
* 實作 `QueryRoutingEngine.java`。先拿問題向量與 `ddb_semantic_cache` 比對距離。
* **若快取未命中 (Miss):** 改查詢 `ddb_knowledge_chunks` 撈取 Top-k 文本，組裝 Prompt 送給 Gemini Pro，並將結果非同步地寫回 `ddb_semantic_cache`。

### 階段四：自動化測試 Harness 驗證 (Harness Evaluation)
* 建立一個簡單的 CLI 啟動器 `AgentHarnessRunner.java`。
* 依序輸入兩次語意極度相似、但文字不同的問題（例如："How to handle hot partitions?" 與 "What to do when hot partition occurs in DynamoDB?"），從日誌確認第二次提問完全呈現 `CACHE_HIT` 且 LLM 呼叫次數為 0。

---

## 6. 常用 PostgreSQL 查詢語法 (Useful Queries)

### 6.0. 互動式資料庫介面 (Interactive DB UI)
本專案配置了 **pgweb** 作為輕量級資料庫瀏覽器，可透過瀏覽器直接執行 SQL 與查看向量資料。

*   **URL:** `http://localhost:5433`
*   **帳號/密碼:** `user` / `password` (於 `docker-compose.yml` 配置)

### 6.1. 語意搜尋 (Semantic Search)
```sql
SELECT text, metadata->>'file_name' as source, 1 - (embedding <=> '[...vector...]') as score FROM ddb_knowledge_chunks ORDER BY score DESC LIMIT 5;
```

### 6.2. 依策略過濾語意搜尋 (Filtered Search)
```sql
SELECT text, 1 - (embedding <=> '[...vector...]') as score FROM ddb_knowledge_chunks WHERE metadata->>'chunking_strategy' = 'recursive-1000-200' ORDER BY score DESC LIMIT 3;
```

### 6.3. 內部相似度測試 (Internal Similarity Test)
```sql
WITH target AS (SELECT embedding FROM ddb_knowledge_chunks LIMIT 1) SELECT text, 1 - (c.embedding <=> target.embedding) as score FROM ddb_knowledge_chunks c, target ORDER BY score DESC LIMIT 5;
```

### 6.4. 依時間戳記排序 (Sort by Timestamp)
```sql
SELECT metadata->>'timestamp' as ts, metadata->>'chunking_strategy' as strategy, LEFT(text, 100) as snippet FROM ddb_knowledge_chunks ORDER BY (metadata->>'timestamp')::timestamp DESC;
```

### 6.5. 檢查重複文本 (Duplicate Check)
```sql
SELECT text, COUNT(*) FROM ddb_knowledge_chunks GROUP BY text HAVING COUNT(*) > 1;
```
