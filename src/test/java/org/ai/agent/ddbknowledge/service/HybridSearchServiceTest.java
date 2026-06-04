package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HybridSearchServiceTest {
    @Autowired
    HybridSearchService hybridSearchService;

    @Autowired
    dev.langchain4j.store.embedding.EmbeddingStore<TextSegment> knowledgeStore;

    @Autowired
    dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    @Test
    void testHybridSearch() {
        // Add some data to ensure search returns results
        TextSegment segment = TextSegment.from("DynamoDB is a fully managed NoSQL database service provided by AWS.");
        knowledgeStore.add(embeddingModel.embed(segment).content(), segment);

        List<EmbeddingMatch<TextSegment>> results = hybridSearchService.search("What is DynamoDB?", 5);
        assertNotNull(results);
        assertTrue(results.size() <= 5);
        assertFalse(results.isEmpty(), "Results should not be empty after adding data");
    }
}
