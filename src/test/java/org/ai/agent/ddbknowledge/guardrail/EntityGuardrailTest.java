package org.ai.agent.ddbknowledge.guardrail;

import org.ai.agent.ddbknowledge.service.EntityGuardrailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class EntityGuardrailTest {
    @Autowired
    private EntityGuardrailService guardrailService;

    @Test
    void shouldRejectDrift_GsiVsLsi() {
        String newQuery = "How to create a GSI?";
        String cachedQuery = "How to create an LSI?";
        String cachedEntities = "LSI";
        
        boolean isValid = guardrailService.isValid(newQuery, cachedQuery, cachedEntities);
        assertThat(isValid).isFalse();
    }

    @Test
    void shouldAcceptSynonym_PartitionKeyVsPk() {
        String newQuery = "What is a PK?";
        String cachedQuery = "What is a partition key?";
        String cachedEntities = "PARTITION KEY";
        
        // This should trigger LLM fallback and return true
        boolean isValid = guardrailService.isValid(newQuery, cachedQuery, cachedEntities);
        assertThat(isValid).isTrue();
    }
}
