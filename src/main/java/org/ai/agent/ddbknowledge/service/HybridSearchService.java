package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.ai.agent.ddbknowledge.repository.KeywordSearchRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {

    private final EmbeddingStore<TextSegment> knowledgeStore;
    private final KeywordSearchRepository keywordSearchRepository;
    private final EmbeddingModel embeddingModel;

    public HybridSearchService(@Qualifier("knowledgeStore") EmbeddingStore<TextSegment> knowledgeStore,
                               KeywordSearchRepository keywordSearchRepository,
                               EmbeddingModel embeddingModel) {
        this.knowledgeStore = knowledgeStore;
        this.keywordSearchRepository = keywordSearchRepository;
        this.embeddingModel = embeddingModel;
    }

    public List<EmbeddingMatch<TextSegment>> search(String query, int maxResults) {
        // 1. Vector Search
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest vectorSearchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults * 2)
                .build();
        List<EmbeddingMatch<TextSegment>> vectorResults = knowledgeStore.search(vectorSearchRequest).matches();

        // 2. Keyword Search
        List<EmbeddingMatch<TextSegment>> keywordResults = keywordSearchRepository.search(query, maxResults * 2);

        // 3. RRF Fusion: Score(d) = sum(1 / (60 + rank(d, type)))
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, EmbeddingMatch<TextSegment>> matchMap = new HashMap<>();

        // Vector ranks
        for (int i = 0; i < vectorResults.size(); i++) {
            EmbeddingMatch<TextSegment> match = vectorResults.get(i);
            String id = match.embeddingId();
            rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (60.0 + (i + 1)));
            matchMap.putIfAbsent(id, match);
        }

        // Keyword ranks
        for (int i = 0; i < keywordResults.size(); i++) {
            EmbeddingMatch<TextSegment> match = keywordResults.get(i);
            String id = match.embeddingId();
            rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (60.0 + (i + 1)));
            matchMap.putIfAbsent(id, match);
        }

        // 4. Return top results sorted by RRF score
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(entry -> {
                    EmbeddingMatch<TextSegment> originalMatch = matchMap.get(entry.getKey());
                    return new EmbeddingMatch<>(
                            entry.getValue(), // Use RRF score as the new score
                            originalMatch.embeddingId(),
                            originalMatch.embedding(),
                            originalMatch.embedded()
                    );
                })
                .collect(Collectors.toList());
    }
}
