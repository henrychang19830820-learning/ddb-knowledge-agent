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