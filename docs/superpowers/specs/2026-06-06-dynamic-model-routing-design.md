# Design Spec: Dynamic Model Routing

**Date**: 2026-06-06
**Topic**: Routing queries to different LLM models based on complexity.
**Status**: Draft

## 1. Objective
Optimize the balance between latency, cost, and response quality by dynamically routing incoming queries to the most appropriate LLM. Simple factual questions will be handled by a fast, inexpensive model, while complex architectural questions will be routed to a more capable, reasoning-heavy model.

## 2. Architecture Overview
The dynamic routing logic will sit **after** the Semantic Cache check (to avoid classifying queries we already know the answer to) and **before** the Generation phase.

### 2.1 Model Roles
Based on the existing `application.yml` pricing tiers:
1. **Classifier Model**: `gemini-3.1-flash-lite`
   - Purpose: Extremely fast, cheap evaluation of query complexity.
2. **Simple Tier Model**: `gemini-3.1-flash-lite`
   - Purpose: Answering factual, direct lookups (e.g., API limits, definitions).
3. **Complex Tier Model**: `gemini-3.5-flash`
   - Purpose: Answering architectural, modeling, or troubleshooting questions.

### 2.2 Data Flow
1. **Cache Miss**: The `QueryRoutingService` determines the query is new.
2. **Classification**: `ModelRoutingService` prompts the Classifier Model to score the query complexity (1-10).
3. **Retrieval**: `HybridSearchService` fetches relevant context (Vector + FTS).
4. **Generation**: `QueryRoutingService` uses the score to select the appropriate ChatModel (Simple or Complex) for the final response.

## 3. Classifier Design

### 3.1 Prompt Strategy
To minimize latency and token usage, the classifier prompt must enforce a strict, parseable output (e.g., just the integer score).

**System Prompt:**
> You are a query router for a DynamoDB technical assistant. Evaluate the complexity of the following user query on a scale of 1 to 10.
> 
> Criteria:
> - Score 1-5 (Simple): Direct factual lookups, definitions, API limits, basic syntax.
> - Score 6-10 (Complex): Data modeling, table design, performance troubleshooting, comparing alternatives.
> 
> Output ONLY the integer score. Do not explain your reasoning.

### 3.2 Error Handling
If the Classifier Model fails or returns unparseable text, the system will default to the **Complex Tier Model** to prioritize quality over cost.

## 4. Java Implementation Details

### 4.1 Configuration Changes (`AgentConfig.java`)
Currently, LangChain4j auto-configures a single `ChatLanguageModel` and `StreamingChatLanguageModel`. We must switch to manual `@Bean` definitions to configure multiple models pointing to different model names but sharing the same API key.

```java
@Bean
@Qualifier("classifierModel")
public ChatLanguageModel classifierModel(...) { ... }

@Bean
@Qualifier("simpleChatModel")
public StreamingChatLanguageModel simpleChatModel(...) { ... }

@Bean
@Qualifier("complexChatModel")
public StreamingChatLanguageModel complexChatModel(...) { ... }
```

### 4.2 Application Properties (`application.yml`)
Add configuration for the routing threshold to allow tuning without code changes.
```yaml
agent:
  routing:
    complexity-threshold: 5
```

### 4.3 Service Updates
- Create `ModelRoutingService.java` to handle the classification prompt and parsing.
- Update `QueryRoutingService.java` to inject the new models, call the router, and conditionally use the selected `StreamingChatLanguageModel`. Ensure the `AuditService` logs the correct model name used for generation.

### 4.4 Distributed Auditing Updates
Because a single user request will now result in multiple LLM calls (Classifier + Generator), the audit schema must be updated to link these calls.
1. **Schema Update**: Alter `request_audit_logs` to add a `trace_id UUID` column.
2. **DTO Update**: Add `String traceId` to `AuditRecord`.
3. **Logic Update**: `QueryRoutingService` must generate a single `UUID` at the start of `ask` and `askStreaming` and pass this `trace_id` into both the Classifier's audit log and the Generator's audit log.

## 5. Success Criteria
- The system successfully classifies queries.
- Simple queries invoke the cheaper Lite model.
- Complex queries invoke the more capable Flash model.
- Audit logs accurately reflect the `model_name` used for the final generation step.
- The total response time for simple queries remains low despite the added classification step.
