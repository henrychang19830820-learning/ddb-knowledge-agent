package org.ai.agent.ddbknowledge.tool;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.ai.agent.ddbknowledge.audit.AuditContext;
import org.ai.agent.ddbknowledge.audit.AuditContextHolder;
import org.ai.agent.ddbknowledge.service.HybridSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentationToolTest {

    @Mock
    private HybridSearchService hybridSearchService;

    @InjectMocks
    private DocumentationTool tool;

    @AfterEach
    void clearContext() {
        AuditContextHolder.clear();
    }

    @Test
    void testSearchDocumentation() {
        when(hybridSearchService.search("test query", 5)).thenReturn(
            List.of(new EmbeddingMatch<>(0.9, "1", null, TextSegment.from("Document result")))
        );

        String result = tool.searchDocumentation("test query");

        assertTrue(result.contains("Document result"));
        verify(hybridSearchService).search("test query", 5);
    }

    @Test
    void recordsFileNameIntoAuditContextAndTagsChunk() {
        AuditContext context = AuditContext.builder().traceId("t1").build();
        AuditContextHolder.set(context);

        TextSegment segment = TextSegment.from(
            "Chunk body",
            Metadata.from(Map.of("file_name", "WorkingWithDynamo.md"))
        );
        when(hybridSearchService.search("dynamo", 5)).thenReturn(
            List.of(new EmbeddingMatch<>(0.9, "1", null, segment))
        );

        String result = tool.searchDocumentation("dynamo");

        assertTrue(context.getRetrievedSources().contains("WorkingWithDynamo.md"));
        assertTrue(result.contains("[Source: WorkingWithDynamo.md]"));
        assertTrue(result.contains("Chunk body"));
    }

    @Test
    void skipsChunkWithoutFileName() {
        AuditContext context = AuditContext.builder().traceId("t2").build();
        AuditContextHolder.set(context);

        when(hybridSearchService.search("nometa", 5)).thenReturn(
            List.of(new EmbeddingMatch<>(0.9, "1", null, TextSegment.from("Untagged body")))
        );

        String result = tool.searchDocumentation("nometa");

        assertTrue(context.getRetrievedSources().isEmpty());
        assertFalse(result.contains("[Source:"));
        assertTrue(result.contains("Untagged body"));
    }
}
