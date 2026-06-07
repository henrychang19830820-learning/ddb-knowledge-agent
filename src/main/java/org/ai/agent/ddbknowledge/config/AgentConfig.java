package org.ai.agent.ddbknowledge.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentConfig {

    @Value("${langchain4j.google-ai-studio.chat-model.api-key}")
    private String apiKey;

    @Value("${agent.routing.classifier-model}")
    private String classifierModelName;

    @Value("${agent.routing.simple-tier-model}")
    private String simpleTierModelName;

    @Value("${agent.routing.medium-tier-model}")
    private String mediumTierModelName;

    @Value("${agent.routing.complex-tier-model}")
    private String complexTierModelName;

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("classifierModel")
    public ChatLanguageModel classifierModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(classifierModelName)
                .build();
    }

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("simpleChatModel")
    public ChatLanguageModel simpleChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(simpleTierModelName)
                .build();
    }

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("mediumChatModel")
    public ChatLanguageModel mediumChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(mediumTierModelName)
                .build();
    }

    @Bean
    @org.springframework.beans.factory.annotation.Qualifier("complexChatModel")
    public ChatLanguageModel complexChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(complexTierModelName)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean("knowledgeStore")
    @Primary
    public EmbeddingStore<TextSegment> knowledgeStore() {
        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("ddb_agent")
                .user("user")
                .password("password")
                .table("ddb_knowledge_chunks")
                .dimension(384)
                .build();
    }

    @Bean("cacheStore")
    public EmbeddingStore<TextSegment> cacheStore() {
        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("ddb_agent")
                .user("user")
                .password("password")
                .table("ddb_semantic_cache")
                .dimension(384)
                .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                        .storageMode(MetadataStorageMode.COLUMN_PER_KEY)
                        .columnDefinitions(java.util.Collections.singletonList("original_query TEXT"))
                        .build())
                .build();
    }
}
