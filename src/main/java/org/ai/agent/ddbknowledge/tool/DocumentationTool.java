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
        log.info("TOOL_START: searchDocumentation for query '{}'", query);
        List<EmbeddingMatch<TextSegment>> matches = hybridSearchService.search(query, 5);
        log.info("TOOL_END: Found {} matches for query '{}'", matches.size(), query);
        
        String result;
        if (matches.isEmpty()) {
            result = "No relevant documentation found for the query.";
        } else {
            result = matches.stream()
                    .map(match -> match.embedded().text())
                    .collect(Collectors.joining("\n\n"));
        }

        // Directly record execution to AuditContext for high-fidelity logs
        org.ai.agent.ddbknowledge.audit.AuditContext context = org.ai.agent.ddbknowledge.audit.AuditContextHolder.get();
        if (context != null) {
            context.addToolExecution("searchDocumentation", query, result);
        }

        return result;
    }
}