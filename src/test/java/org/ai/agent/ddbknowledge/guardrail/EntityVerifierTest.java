package org.ai.agent.ddbknowledge.guardrail;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class EntityVerifierTest {

    private ChatLanguageModel classifierModel;
    private EntityVerifier entityVerifier;

    @BeforeEach
    void setUp() {
        classifierModel = Mockito.mock(ChatLanguageModel.class);
        entityVerifier = new EntityVerifier(classifierModel);
    }

    @Test
    void areEquivalent_ReturnsTrue_WhenModelReturnsEquivalent() {
        when(classifierModel.generate(anyString())).thenReturn("EQUIVALENT");

        boolean result = entityVerifier.areEquivalent("How to create a GSI?", "GSI creation steps");

        assertThat(result).isTrue();
    }

    @Test
    void areEquivalent_ReturnsFalse_WhenModelReturnsDifferent() {
        when(classifierModel.generate(anyString())).thenReturn("DIFFERENT");

        boolean result = entityVerifier.areEquivalent("How to create a GSI?", "What is an LSI?");

        assertThat(result).isFalse();
    }
}
