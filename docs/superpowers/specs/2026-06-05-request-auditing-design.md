# Design Spec: Request Auditing & Cost Metrics

**Date**: 2026-06-05
**Topic**: Recording token usage, model costs, and latency metrics for each user request.
**Status**: Draft

## 1. Objective
Implement a robust auditing system to track the efficiency and cost of the technical assistant. This system will record every interaction, allowing for deep analysis of LLM expenditures and performance metrics (specifically Time To First Token).

## 2. Architecture Overview
The system will use a **PostgreSQL-based** auditing table. Data collection will be integrated directly into the `QueryRoutingService` to ensure high-precision latency tracking, especially for streaming responses.

- **Storage**: `request_audit_logs` table in the existing PostgreSQL database.
- **Processing**: A new `AuditService` will handle cost calculation and asynchronous database persistence.
- **Latency Tracking**: Uses `System.nanoTime()` to capture Time To First Token (TTFT) and Total Latency.

## 3. Database Design

### 3.1. Audit Schema
```sql
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
    ttft_ms BIGINT,             -- Time to first token
    total_latency_ms BIGINT,    -- Total request-to-response time
    
    -- Metadata
    is_cache_hit BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_created_at ON request_audit_logs(created_at);
```

## 4. Cost Configuration

### 4.1. Model Pricing (`application.yml`)
Pricing is defined per 1,000,000 tokens based on available Gemini models as of June 2026.

| Model Name | Input Price (per 1M) | Output Price (per 1M) |
| :--- | :--- | :--- |
| `gemini-3.5-flash` | $0.10 | $0.40 |
| `gemini-3-flash` | $0.075 | $0.30 |
| `gemini-3.1-flash-lite` | $0.05 | $0.20 |
| `gemini-2.5-flash` | $0.075 | $0.30 |
| `gemini-2.5-flash-lite` | $0.05 | $0.20 |

*Note: Default pricing of $0.0 will be applied to unknown models or Cache Hits.*

## 5. Implementation Details

### 5.1. Latency Measurement Strategy
1.  **Start Time**: Captured at the beginning of the `ask` or `askStreaming` method.
2.  **TTFT (Streaming)**: Recorded in the first call to `onNext(token)`.
3.  **TTFT (Non-Streaming)**: Equal to Total Latency.
4.  **End Time**: Recorded in `onComplete()` or when the response is returned.

### 5.2. Token Extraction
Tokens will be extracted from the LangChain4j `Response<AiMessage>` object:
- `response.tokenUsage().inputTokenCount()`
- `response.tokenUsage().outputTokenCount()`

### 5.3. Asynchronous Persistence
To minimize impact on user-perceived latency, `AuditService.recordAudit()` will be marked as `@Async`, offloading the SQL insert and cost calculation to a background thread pool.

## 6. Success Criteria
- Every request (Cache Hit or Miss) creates a record in `request_audit_logs`.
- Cache Hits show 0 token usage and 0 cost.
- TTFT is accurately captured for streaming responses.
- Costs are calculated to at least 8 decimal places of precision.

## 7. Future Considerations
- Dashboard for cost visualization (via PGWeb or a new UI).
- Alerting for high-cost requests.
