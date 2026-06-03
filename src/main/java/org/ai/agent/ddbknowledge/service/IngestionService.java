package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocuments;

@Service
@Slf4j
public class IngestionService {

    private final EmbeddingStore<TextSegment> knowledgeStore;
    private final EmbeddingModel embeddingModel;

    public IngestionService(@Qualifier("knowledgeStore") EmbeddingStore<TextSegment> knowledgeStore,
                            EmbeddingModel embeddingModel) {
        this.knowledgeStore = knowledgeStore;
        this.embeddingModel = embeddingModel;
    }

    public void ingest(String directoryPath, String chunkingStrategy) {
        log.info("Starting ingestion from directory: {} with strategy: {}", directoryPath, chunkingStrategy);
        
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:*.md");
        List<Document> documents = loadDocuments(Paths.get(directoryPath), pathMatcher, new TextDocumentParser());
        
        String currentTimestamp = Instant.now().toString();

        // Inject strategy and timestamp into metadata
        documents.forEach(doc -> {
            doc.metadata().put("chunking_strategy", chunkingStrategy);
            doc.metadata().put("timestamp", currentTimestamp);
        });
        
        log.info("Loaded {} Markdown documents", documents.size());

        if (documents.isEmpty()) {
            log.warn("No documents found in {}. Skipping ingestion.", directoryPath);
            return;
        }

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1000, 200))
                .embeddingModel(embeddingModel)
                .embeddingStore(knowledgeStore)
                .build();

    ingestor.ingest(documents);
        log.info("Ingestion completed successfully");
    }

    public void removeAll() {
        log.info("Removing all documents from the knowledge store");
        knowledgeStore.removeAll();
    }
}
