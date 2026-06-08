# Native Streaming & Real TTFT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement true native streaming using LangChain4j's `TokenStream` to ensure accurate Time To First Token (TTFT) metrics and responsive user interaction without artificial workarounds.

**Architecture:** Re-enable `StreamingChatLanguageModel` in the configuration. Refactor `QueryRoutingService` to use a `TokenStream`-returning Assistant interface. Capture real TTFT when the first token actually arrives from the model and link all model calls via `trace_id`.

**Tech Stack:** Java 21, Spring Boot, LangChain4j.

---

### Task 1: Re-enable Streaming Model Beans

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java`

- [ ] **Step 1: Update AgentConfig to return StreamingChatLanguageModel**

Change the return types and builder classes for `simpleChatModel`, `mediumChatModel`, and `complexChatModel`.

```java
    @Bean
    @Qualifier("simpleChatModel")
    public StreamingChatLanguageModel simpleChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(simpleTierModelName)
                .build();
    }

    @Bean
    @Qualifier("mediumChatModel")
    public StreamingChatLanguageModel mediumChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(mediumTierModelName)
                .build();
    }

    @Bean
    @Qualifier("complexChatModel")
    public StreamingChatLanguageModel complexChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(complexTierModelName)
                .build();
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java
git commit -m "feat: re-enable streaming chat models for simple, medium, and complex tiers"
```

---

### Task 2: Refactor QueryRoutingService for Native TokenStream

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Update Assistant interface and Class Fields**

Change `Assistant` to return `TokenStream`. Update generator model fields to `StreamingChatLanguageModel`.

- [ ] **Step 2: Implement Reactive Token Consumption in askStreaming**

Re-implement the `askStreaming` logic to use native `TokenStream` hooks. Remove the `Thread.sleep` and `answer.split` logic.

```java
// Inside askStreaming after Cache Miss and Model Selection
        Assistant assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(selectedModel)
                .chatMemory(dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10))
                .tools(documentationTool)
                .systemMessageProvider(chatMemoryId -> systemPrompt)
                .build();

        StringBuilder fullResponse = new StringBuilder();
        java.util.concurrent.atomic.AtomicBoolean firstTokenReceived = new java.util.concurrent.atomic.AtomicBoolean(false);
        long[] ttft = new long[]{0};

        log.info("Starting native ReAct TokenStream [traceId={}]", traceId);
        assistant.chat(query)
                .onNext(token -> {
                    if (firstTokenReceived.compareAndSet(false, true)) {
                        ttft[0] = (System.nanoTime() - startTime) / 1_000_000;
                        log.info("Real First Token received [traceId={}] at {}ms", traceId, ttft[0]);
                    }
                    fullResponse.append(token);
                    handler.onNext(token);
                })
                .onCompleteResponse(response -> {
                    long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                    log.info("Native stream completed [traceId={}] in {}ms", traceId, totalLatency);
                    
                    int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
                    int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

                    auditService.recordAudit(AuditRecord.builder()
                            .queryText(query)
                            .modelName(selectedModelName)
                            .inputTokens(inputTokens)
                            .outputTokens(outputTokens)
                            .ttftMs(ttft[0])
                            .totalLatencyMs(totalLatency)
                            .isCacheHit(false)
                            .traceId(traceId)
                            .complexityScore(complexityScore)
                            .build());

                    Metadata metadata = new Metadata();
                    metadata.put("original_query", query);
                    cacheStore.add(queryEmbedding, TextSegment.from(fullResponse.toString(), metadata));

                    handler.onComplete(Response.from(AiMessage.from(fullResponse.toString()), response.tokenUsage(), response.finishReason()));
                })
                .onError(error -> {
                    log.error("Native Streaming Error [traceId={}]", traceId, error);
                    long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                    auditService.recordAudit(AuditRecord.builder()
                            .queryText(query)
                            .modelName(selectedModelName)
                            .inputTokens(0)
                            .outputTokens(0)
                            .ttftMs(ttft[0])
                            .totalLatencyMs(totalLatency)
                            .isCacheHit(false)
                            .traceId(traceId)
                            .complexityScore(complexityScore)
                            .build());
                    handler.onError(error);
                })
                .start(); // EXPLICIT START IS REQUIRED
```

- [ ] **Step 3: Verify and Commit**

Run: `./gradlew classes`

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: implement native TokenStream handling with real TTFT tracking"
```

---

### Task 3: Final E2E Verification

- [ ] **Step 1: Start Application**

Run: `fuser -k 8090/tcp || true; ./gradlew bootRun`

- [ ] **Step 2: Trigger Request and Observe Logs**

Run: `curl -v "http://localhost:8090/ask-stream?question=Explain+GSI+in+DynamoDB."`
Verify tokens stream in immediately.

- [ ] **Step 3: Check Audit Logs for TTFT**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "SELECT query_text, model_name, ttft_ms, total_latency_ms FROM request_audit_logs ORDER BY created_at DESC LIMIT 2;"`
Expected: `ttft_ms` is significantly lower than `total_latency_ms` and represents the physical arrival of the first token.

- [ ] **Step 4: Cleanup and Final Commit**

```bash
git commit -am "chore: finalize native streaming implementation"
```
