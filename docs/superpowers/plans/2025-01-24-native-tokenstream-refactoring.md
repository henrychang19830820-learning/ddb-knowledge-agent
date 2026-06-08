# Native TokenStream Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `QueryRoutingService` to use true native `TokenStream` handling instead of the simulated word-by-word streaming workaround.

**Architecture:** Update the LangChain4j AI Service interface to return `TokenStream`, allowing reactive consumption of the model's output as it arrives. This enables real-time TTFT (Time To First Token) tracking and improves UI responsiveness without artificial delays.

**Tech Stack:** Java, LangChain4j, Spring Boot

---

### Task 1: Update Assistant Interface and Imports

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Update imports and Assistant interface**

```java
// Add missing imports
import dev.langchain4j.service.TokenStream;
import java.util.concurrent.atomic.AtomicBoolean;

// ... inside QueryRoutingService class ...
    interface Assistant {
        TokenStream chat(String message);
    }
```

### Task 2: Refactor askStreaming Method

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Replace simulated streaming with native TokenStream handling**

```java
        Assistant assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(selectedModel)
                .chatMemory(dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10))
                .tools(documentationTool)
                .systemMessageProvider(chatMemoryId -> systemPrompt)
                .build();

        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean firstTokenReceived = new AtomicBoolean(false);
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

                    handler.onComplete(Response.from(dev.langchain4j.data.message.AiMessage.from(fullResponse.toString()), response.tokenUsage(), response.finishReason()));
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
                .start(); // MUST CALL START
```

### Task 3: Verification

- [ ] **Step 1: Compile the project**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

### Task 4: Commit Changes

- [ ] **Step 1: Commit the refactoring**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: implement native TokenStream handling with real TTFT tracking"
```
