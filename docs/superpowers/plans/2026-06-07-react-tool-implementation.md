# ReAct Loop and Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the RAG flow into a ReAct loop with a max of 10 turns, exposing hybrid search as a tool, and updating the prompt to allow using internal knowledge.

**Architecture:** Create a `DocumentationTool` wrapping `HybridSearchService`. Update `QueryRoutingService` to dynamically build an `AiServices` Assistant per request (to select the appropriate model and inject trace-level details), using `MessageWindowChatMemory` limited to 10 messages.

**Tech Stack:** Java 21, Spring Boot, LangChain4j.

---

### Task 1: Create Documentation Tool

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java`
- Create: `src/test/java/org/ai/agent/ddbknowledge/tool/DocumentationToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.ai.agent.ddbknowledge.tool;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.ai.agent.ddbknowledge.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentationToolTest {

    @Mock
    private HybridSearchService hybridSearchService;

    @InjectMocks
    private DocumentationTool tool;

    @Test
    void testSearchDocumentation() {
        when(hybridSearchService.search("test query", 5)).thenReturn(
            List.of(new EmbeddingMatch<>(0.9, "1", null, TextSegment.from("Document result")))
        );

        String result = tool.searchDocumentation("test query");
        
        assertTrue(result.contains("Document result"));
        verify(hybridSearchService).search("test query", 5);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.tool.DocumentationToolTest`
Expected: FAIL (compilation error)

- [ ] **Step 3: Write minimal implementation**

```java
package org.ai.agent.ddbknowledge.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.service.HybridSearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentationTool {

    private final HybridSearchService hybridSearchService;

    @Tool("Search the official Amazon DynamoDB documentation. Use this to look up specific APIs, configurations, best practices, and internal workings of DynamoDB.")
    public String searchDocumentation(String query) {
        log.info("Tool execution: searchDocumentation for query '{}'", query);
        List<EmbeddingMatch<TextSegment>> matches = hybridSearchService.search(query, 5);
        if (matches.isEmpty()) {
            return "No relevant documentation found for the query.";
        }
        return matches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n"));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests org.ai.agent.ddbknowledge.tool.DocumentationToolTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java src/test/java/org/ai/agent/ddbknowledge/tool/DocumentationToolTest.java
git commit -m "feat: create DocumentationTool for ReAct loop"
```

---

### Task 2: Refactor QueryRoutingService for ReAct

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Inject DocumentationTool**

In `QueryRoutingService.java`, add `private final DocumentationTool documentationTool;` and inject it via the constructor. Remove direct usage of `HybridSearchService` inside `askStreaming` since the tool handles it now.

- [ ] **Step 2: Define Assistant interface**

Inside `QueryRoutingService`, define a package-private interface:
```java
    interface Assistant {
        dev.langchain4j.service.TokenStream chat(String message);
    }
```

- [ ] **Step 3: Refactor askStreaming to use AiServices**

Update `askStreaming` logic for Cache Miss:
1. Retain complexity check and model selection.
2. Build an `Assistant` instance dynamically for each request.
3. Update the prompt to tell the agent it can use its own knowledge but MUST explicitly distinguish between tool-provided context and model training data.
4. Execute `assistant.chat(query)` with the `StreamingResponseHandler` logic for capturing TTFT and latency.

```java
// Inside askStreaming after Cache Miss logic
        // 4. Dynamic Model Selection
        int complexityScore = modelRoutingService.getComplexityScore(query, traceId);
        
        StreamingChatLanguageModel selectedModel;
        String selectedModelName;

        if (complexityScore <= simpleThreshold) {
            selectedModel = simpleChatModel;
            selectedModelName = simpleTierModelName;
        } else if (complexityScore <= mediumThreshold) {
            selectedModel = mediumChatModel;
            selectedModelName = mediumTierModelName;
        } else {
            selectedModel = complexChatModel;
            selectedModelName = complexTierModelName;
        }
        
        log.info("Selected model {} [traceId={}] with score {}", selectedModelName, traceId, complexityScore);

        String systemPrompt = "You are a DynamoDB expert. " +
                "You have access to a tool to search the official documentation. You should use it to look up specific technical details. " +
                "You may use your own training data to answer, but you MUST explicitly mention in your answer what information comes from the documentation context and what comes from your model training data. " +
                "Do not hallucinate technical specifications.";

        Assistant assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(selectedModel)
                .chatMemory(dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10))
                .tools(documentationTool)
                .systemMessageProvider(chatMemoryId -> systemPrompt)
                .build();

        StringBuilder fullResponse = new StringBuilder();

        assistant.chat(query)
                .onNext(token -> {
                    if (fullResponse.length() == 0) { // Using string builder length as a first token indicator
                        long ttft = (System.nanoTime() - startTime) / 1_000_000;
                        // Record a partial audit log or store ttft to pass to onComplete
                    }
                    fullResponse.append(token);
                    handler.onNext(token);
                })
                .onComplete(response -> {
                    long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                    int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
                    int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;
                    
                    // We don't have TTFT easily available in the TokenStream API without a wrapper, 
                    // so we'll approximate TTFT as total latency minus a fraction, or we can use a dedicated handler wrapper if needed.
                    // For simplicity, let's keep track of ttft in a effectively final array
                    // ... see Step 4 for proper streaming handler wrapper.
                })
                .onError(handler::onError);
```

- [ ] **Step 4: Fix TokenStream TTFT Tracking**

Since `AiServices` returns a `TokenStream` and doesn't directly take `StreamingResponseHandler`, we use a 1-element array to track `ttft`.

```java
// Inside askStreaming after model selection
        String systemPrompt = "You are a DynamoDB expert. " +
                "You have access to a tool to search the official documentation. You should use it to look up specific technical details. " +
                "You may use your own training data to answer, but you MUST explicitly mention in your answer what information comes from the documentation context and what comes from your model training data. " +
                "Do not hallucinate technical specifications.";

        Assistant assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(selectedModel)
                .chatMemory(dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(10))
                .tools(documentationTool)
                .systemMessageProvider(chatMemoryId -> systemPrompt)
                .build();

        StringBuilder fullResponse = new StringBuilder();
        long[] ttft = new long[]{0};
        boolean[] firstTokenReceived = new boolean[]{false};

        assistant.chat(query)
                .onNext(token -> {
                    if (!firstTokenReceived[0]) {
                        ttft[0] = (System.nanoTime() - startTime) / 1_000_000;
                        firstTokenReceived[0] = true;
                    }
                    fullResponse.append(token);
                    handler.onNext(token);
                })
                .onComplete(response -> {
                    long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
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

                    log.info("Updating semantic cache with new streamed answer [traceId={}]", traceId);
                    Metadata metadata = new Metadata();
                    metadata.put("original_query", query);
                    cacheStore.add(queryEmbedding, TextSegment.from(fullResponse.toString(), metadata));

                    handler.onComplete(Response.from(dev.langchain4j.data.message.AiMessage.from(fullResponse.toString()), response.tokenUsage(), response.finishReason()));
                })
                .onError(error -> {
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
                });
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: use ReAct loop via AiServices with 10 max turns and explicit knowledge separation"
```
