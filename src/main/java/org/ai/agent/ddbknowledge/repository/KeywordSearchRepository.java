package org.ai.agent.ddbknowledge.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class KeywordSearchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KeywordSearchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<EmbeddingMatch<TextSegment>> search(String query, int limit) {
        String sql = """
                SELECT embedding_id, text, metadata, 
                       ts_rank(content_tokens, plainto_tsquery('english', ?)) as score
                FROM ddb_knowledge_chunks
                WHERE content_tokens @@ plainto_tsquery('english', ?)
                ORDER BY score DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String id = rs.getString("embedding_id");
            String text = rs.getString("text");
            String metadataJson = rs.getString("metadata");
            double score = rs.getDouble("score");

            Map<String, String> metadata = new HashMap<>();
            try {
                if (metadataJson != null) {
                    Map<String, Object> map = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                    map.forEach((k, v) -> {
                        if (v != null) {
                            metadata.put(k, v.toString());
                        }
                    });
                }
            } catch (JsonProcessingException e) {
                log.error("Failed to parse metadata JSON: {}", metadataJson, e);
            }

            return new EmbeddingMatch<>(
                    score,
                    id,
                    null, // No embedding vector needed for keyword search results
                    TextSegment.from(text, dev.langchain4j.data.document.Metadata.from(metadata))
            );
        }, query, query, limit);
    }
}
