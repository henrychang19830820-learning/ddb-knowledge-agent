package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.audit.AuditContext;
import org.ai.agent.ddbknowledge.audit.AuditContextHolder;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.ai.agent.ddbknowledge.tool.DocumentationTool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class QueryRoutingService {

    private final ModelRoutingService modelRoutingService;
    private final StreamingChatLanguageModel simpleChatModel;
    private final StreamingChatLanguageModel mediumChatModel;
    private final StreamingChatLanguageModel complexChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> cacheStore;
    private final DocumentationTool documentationTool;
    private final AuditService auditService;

    @Value("${agent.cache.semantic-threshold:0.92}")
    private double cacheThreshold;

    @Value("${agent.routing.simple-threshold:3}")
    private int simpleThreshold;

    @Value("${agent.routing.medium-threshold:6}")
    private int mediumThreshold;

    @Value("${agent.routing.simple-tier-model}")
    private String simpleTierModelName;

    @Value("${agent.routing.medium-tier-model}")
    private String mediumTierModelName;

    @Value("${agent.routing.complex-tier-model}")
    private String complexTierModelName;


    public QueryRoutingService(ModelRoutingService modelRoutingService,
                              @Qualifier("simpleChatModel") StreamingChatLanguageModel simpleChatModel,
                              @Qualifier("mediumChatModel") StreamingChatLanguageModel mediumChatModel,
                              @Qualifier("complexChatModel") StreamingChatLanguageModel complexChatModel,
                              EmbeddingModel embeddingModel,
                              @Qualifier("cacheStore") EmbeddingStore<TextSegment> cacheStore,
                              DocumentationTool documentationTool,
                              AuditService auditService) {
        this.modelRoutingService = modelRoutingService;
        this.simpleChatModel = simpleChatModel;
        this.mediumChatModel = mediumChatModel;
        this.complexChatModel = complexChatModel;
        this.embeddingModel = embeddingModel;
        this.cacheStore = cacheStore;
        this.documentationTool = documentationTool;
        this.auditService = auditService;
    }

    interface Assistant {
        TokenStream chat(String message);
    }

    public void askStreaming(String query, StreamingResponseHandler<AiMessage> handler) {
        String traceId = UUID.randomUUID().toString();
        log.info("Processing streaming query [traceId={}]: {}", traceId, query);
        long startTime = System.nanoTime();

        // Run in background thread to return SseEmitter immediately and prevent connection blocking
        new Thread(() -> {
            // Capture context in a final variable to be accessed by callbacks safely
            final AuditContext auditContext = AuditContext.builder().traceId(traceId).build();
            AuditContextHolder.set(auditContext);
            
            try {
                // 1. Generate Query Embedding (Needed for cache)
                Embedding queryEmbedding = embeddingModel.embed(query).content();

                // 2. Check Semantic Cache
                EmbeddingSearchRequest cacheSearchRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(1)
                        .minScore(cacheThreshold)
                        .build();
                List<EmbeddingMatch<TextSegment>> cacheMatches = cacheStore.search(cacheSearchRequest).matches();

                if (!cacheMatches.isEmpty()) {
                    log.info("CACHE_HIT [traceId={}]: Found similar query with score {}", traceId, cacheMatches.get(0).score());
                    String cachedAnswer = cacheMatches.get(0).embedded().text();
                    
                    long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                    auditService.recordAudit(AuditRecord.builder()
                            .queryText(query)
                            .fullPrompt("CACHE_HIT")
                            .modelName(simpleTierModelName)
                            .inputTokens(0)
                            .outputTokens(0)
                            .ttftMs(totalLatency)
                            .totalLatencyMs(totalLatency)
                            .isCacheHit(true)
                            .traceId(traceId)
                            .build());
                    
                    handler.onNext(cachedAnswer);
                    handler.onComplete(Response.from(AiMessage.from(cachedAnswer)));
                    return;
                }

                log.info("CACHE_MISS [traceId={}]: Proceeding to ReAct loop", traceId);

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

                String systemPrompt = "You are a DynamoDB expert. You have access to a tool to search the official documentation. You should use it to look up specific technical details.\n" +
                        "\n" +
                        "You MUST format your entire response using the following mandatory Markdown template:\n" +
                        "\n" +
                        "### 📚 From Official Documentation\n" +
                        "[Provide information found ONLY using the search tool here. If the tool returned no results, state \"No specific documentation found for this query.\"]\n" +
                        "\n" +
                        "### 🧠 From Expert Knowledge\n" +
                        "[Provide supplementary information from your own training data here to give a more complete or practical answer.]\n" +
                        "\n" +
                        "---\n" +
                        "**Constraint:** Do not hallucinate technical specifications. If there is a conflict, always prioritize information from the documentation tool.";

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
                TokenStream stream = assistant.chat(query);

                stream
                        .onNext(token -> {
                            if (firstTokenReceived.compareAndSet(false, true)) {
                                ttft[0] = (System.nanoTime() - startTime) / 1_000_000;
                                log.info("Real First Token received [traceId={}] at {}ms", traceId, ttft[0]);
                            }
                            fullResponse.append(token);
                            handler.onNext(token);
                        })
                        .onComplete(response -> {
                            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                            log.info("TokenStream completed [traceId={}] in {}ms", traceId, totalLatency);
                            
                            // Access the context via final local variable
                            String finalHighFidelityPrompt = auditContext.getCapturedPrompt();

                            // Serialize tool executions
                            String toolCallsJson = null;
                            try {
                                toolCallsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(auditContext.getToolExecutions());
                            } catch (Exception e) {
                                log.warn("Failed to serialize tool calls for auditing [traceId={}]", traceId, e);
                            }

                            int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
                            int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

                            auditService.recordAudit(AuditRecord.builder()
                                    .queryText(query)
                                    .fullPrompt(finalHighFidelityPrompt)
                                    .modelName(selectedModelName)
                                    .inputTokens(inputTokens)
                                    .outputTokens(outputTokens)
                                    .ttftMs(ttft[0])
                                    .totalLatencyMs(totalLatency)
                                    .isCacheHit(false)
                                    .traceId(traceId)
                                    .complexityScore(complexityScore)
                                    .toolCallsJson(toolCallsJson)
                                    .build());

                            log.info("Updating semantic cache with new answer [traceId={}]", traceId);
                            Metadata metadata = new Metadata();
                            metadata.put("original_query", query);
                            cacheStore.add(queryEmbedding, TextSegment.from(fullResponse.toString(), metadata));

                            handler.onComplete(Response.from(dev.langchain4j.data.message.AiMessage.from(fullResponse.toString()), response.tokenUsage(), response.finishReason()));
                        })
                        .onError(error -> {
                            log.error("Native Streaming Error [traceId={}]", traceId, error);
                            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                            
                            String finalHighFidelityPrompt = auditContext.getCapturedPrompt();

                            // Serialize tool executions
                            String toolCallsJson = null;
                            try {
                                toolCallsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(auditContext.getToolExecutions());
                            } catch (Exception e) {
                                log.warn("Failed to serialize tool calls for auditing [traceId={}]", traceId, e);
                            }

                            auditService.recordAudit(AuditRecord.builder()
                                    .queryText(query)
                                    .fullPrompt(finalHighFidelityPrompt)
                                    .modelName(selectedModelName)
                                    .inputTokens(0)
                                    .outputTokens(0)
                                    .ttftMs(ttft[0])
                                    .totalLatencyMs(totalLatency)
                                    .isCacheHit(false)
                                    .traceId(traceId)
                                    .complexityScore(complexityScore)
                                    .toolCallsJson(toolCallsJson)
                                    .build());
                            handler.onError(error);
                        })
                        .start();
            } catch (Exception e) {
                log.error("Error in async askStreaming [traceId={}]", traceId, e);
                handler.onError(e);
            } finally {
                AuditContextHolder.clear();
            }
        }).start();
    }

    public void clearCache() {
        log.info("Clearing semantic cache");
        cacheStore.removeAll();
    }
}
