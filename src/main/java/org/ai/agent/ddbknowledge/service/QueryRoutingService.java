package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QueryRoutingService {

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> cacheStore;
    private final HybridSearchService hybridSearchService;

    @Value("${agent.cache.semantic-threshold:0.92}")
    private double cacheThreshold;

    public QueryRoutingService(ChatLanguageModel chatModel,
                              StreamingChatLanguageModel streamingChatModel,
                              EmbeddingModel embeddingModel,
                              @Qualifier("cacheStore") EmbeddingStore<TextSegment> cacheStore,
                              HybridSearchService hybridSearchService) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.cacheStore = cacheStore;
        this.hybridSearchService = hybridSearchService;
    }

    public String ask(String query) {
        log.info("Processing query: {}", query);

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
            log.info("CACHE_HIT: Found similar query with score {}", cacheMatches.get(0).score());
            return cacheMatches.get(0).embedded().text();
        }

        log.info("CACHE_MISS: Proceeding to Hybrid Search retrieval");

        // 3. Hybrid Search Retrieval (Combines Vector + Keyword)
        List<EmbeddingMatch<TextSegment>> knowledgeMatches = hybridSearchService.search(query, 5);

        String context = knowledgeMatches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n"));

        // 4. Generate Answer using Gemini
        String systemPrompt = "You are a DynamoDB expert. Answer the user's question using only the provided context from the official documentation. " +
                "If the answer is not in the context, say you don't know. \n\nContext:\n" + context;

        Response<AiMessage> response = chatModel.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(query)
        );
        String answer = response.content().text();

        // 5. Update Cache (Store the query as embedding and the answer as text)
        log.info("Updating semantic cache with new answer");
        Metadata metadata = new Metadata();
        metadata.put("original_query", query);
        cacheStore.add(queryEmbedding, TextSegment.from(answer, metadata));

        return answer;
    }

    public void askStreaming(String query, StreamingResponseHandler<AiMessage> handler) {
        log.info("Processing streaming query: {}", query);

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
            log.info("CACHE_HIT: Found similar query with score {}", cacheMatches.get(0).score());
            String cachedAnswer = cacheMatches.get(0).embedded().text();
            handler.onNext(cachedAnswer);
            handler.onComplete(Response.from(AiMessage.from(cachedAnswer)));
            return;
        }

        log.info("CACHE_MISS: Proceeding to Hybrid Search retrieval");

        // 3. Hybrid Search Retrieval (Combines Vector + Keyword)
        List<EmbeddingMatch<TextSegment>> knowledgeMatches = hybridSearchService.search(query, 5);

        String context = knowledgeMatches.stream()
                .map(match -> match.embedded().text())
                .collect(Collectors.joining("\n\n"));

        // 4. Generate Answer using Gemini (Streaming)
        String systemPrompt = "You are a DynamoDB expert. Answer the user's question using only the provided context from the official documentation. " +
                "If the answer is not in the context, say you don't know. \n\nContext:\n" + context;

        StringBuilder fullResponse = new StringBuilder();

        streamingChatModel.generate(
                java.util.Arrays.asList(SystemMessage.from(systemPrompt), UserMessage.from(query)),
                new StreamingResponseHandler<AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        fullResponse.append(token);
                        handler.onNext(token);
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        // 5. Update Cache
                        log.info("Updating semantic cache with new streamed answer");
                        Metadata metadata = new Metadata();
                        metadata.put("original_query", query);
                        cacheStore.add(queryEmbedding, TextSegment.from(fullResponse.toString(), metadata));

                        handler.onComplete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        handler.onError(error);
                    }
                }
        );
    }

    public void clearCache() {
        log.info("Clearing semantic cache");
        cacheStore.removeAll();
    }
}
