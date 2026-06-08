package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.ai.agent.ddbknowledge.tool.DocumentationTool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

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

    interface Assistant {
        dev.langchain4j.service.Result<String> chat(String message);
    }

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

    public void askStreaming(String query, StreamingResponseHandler<AiMessage> handler) {
        String traceId = UUID.randomUUID().toString();
        log.info("Processing streaming request [traceId={}]: {}", traceId, query);
        long startTime = System.nanoTime();

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

        try {
            log.info("Starting ReAct loop [traceId={}]", traceId);
            dev.langchain4j.service.Result<String> result = assistant.chat(query);
            log.info("ReAct loop completed [traceId={}]", traceId);
            
            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
            String answer = result.content();
            int inputTokens = result.tokenUsage() != null ? result.tokenUsage().inputTokenCount() : 0;
            int outputTokens = result.tokenUsage() != null ? result.tokenUsage().outputTokenCount() : 0;

            // Log TTFT as total reasoning time for ReAct (since nothing streams until reasoning finishes)
            auditService.recordAudit(AuditRecord.builder()
                    .queryText(query)
                    .modelName(selectedModelName)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .ttftMs(totalLatency) 
                    .totalLatencyMs(totalLatency)
                    .isCacheHit(false)
                    .traceId(traceId)
                    .complexityScore(complexityScore)
                    .build());

            log.info("Updating semantic cache [traceId={}]", traceId);
            Metadata metadata = new Metadata();
            metadata.put("original_query", query);
            cacheStore.add(queryEmbedding, TextSegment.from(answer, metadata));

            // Manually stream words to maintain UI responsiveness
            String[] tokens = answer.split("(?<=\\s)|(?=\\n)");
            for (String token : tokens) {
                handler.onNext(token);
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            
            handler.onComplete(Response.from(AiMessage.from(answer), result.tokenUsage(), dev.langchain4j.model.output.FinishReason.STOP));

        } catch (Exception error) {
            log.error("Error in ReAct loop [traceId={}]", traceId, error);
            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
            auditService.recordAudit(AuditRecord.builder()
                    .queryText(query)
                    .modelName(selectedModelName)
                    .inputTokens(0)
                    .outputTokens(0)
                    .ttftMs(totalLatency)
                    .totalLatencyMs(totalLatency)
                    .isCacheHit(false)
                    .traceId(traceId)
                    .complexityScore(complexityScore)
                    .build());
            handler.onError(error);
        }
    }

    public void clearCache() {
        log.info("Clearing semantic cache");
        cacheStore.removeAll();
    }
}
