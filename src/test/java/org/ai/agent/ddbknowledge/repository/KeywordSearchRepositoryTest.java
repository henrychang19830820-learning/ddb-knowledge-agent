package org.ai.agent.ddbknowledge.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class KeywordSearchRepositoryTest {
    @Autowired(required = false)
    KeywordSearchRepository repository;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void testKeywordSearch() {
        assertNotNull(repository, "Repository should be defined");
        
        // Insert dummy data for the test
        jdbcTemplate.execute("INSERT INTO ddb_knowledge_chunks (text, metadata) VALUES ('DynamoDB is a NoSQL database service.', '{}')");

        List<?> results = repository.search("DynamoDB", 5);
        assertFalse(results.isEmpty(), "Should find results for 'DynamoDB'");
    }
}
