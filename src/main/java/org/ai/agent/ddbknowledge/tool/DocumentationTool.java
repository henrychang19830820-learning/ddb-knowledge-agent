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