package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelRoutingServiceTest {

    @Mock
    private ChatLanguageModel mockClassifier;

    @Mock
    private AuditService mockAuditService;

    @Test
    void testRouteSimpleQuery() {
        when(mockClassifier.generate(anyList())).thenReturn(
            new Response<>(AiMessage.from("3"), new TokenUsage(10, 1), FinishReason.STOP)
        );

        ModelRoutingService service = new ModelRoutingService(mockClassifier, mockAuditService);
        service.setClassifierModelName("gemini-lite");
        
        int score = service.getComplexityScore("What is DynamoDB?", "00000000-0000-0000-0000-000000000123");
        
        assertEquals(3, score);
        assertFalse(score > 5);
        
        ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(mockAuditService).recordAudit(captor.capture());
        
        AuditRecord record = captor.getValue();
        assertEquals("What is DynamoDB?", record.getQueryText());
        assertEquals("gemini-lite", record.getModelName());
        assertEquals("00000000-0000-0000-0000-000000000123", record.getTraceId());
        assertEquals(3, record.getComplexityScore());
    }

    @Test
    void testRouteComplexQuery() {
        when(mockClassifier.generate(anyList())).thenReturn(
            new Response<>(AiMessage.from("8"), new TokenUsage(15, 2), FinishReason.STOP)
        );

        ModelRoutingService service = new ModelRoutingService(mockClassifier, mockAuditService);
        service.setClassifierModelName("gemini-lite");
        
        int score = service.getComplexityScore("How do I implement a global secondary index with overloading for a multi-tenant application?", "00000000-0000-0000-0000-000000000456");
        
        assertEquals(8, score);
        assertTrue(score > 5);
        verify(mockAuditService).recordAudit(any(AuditRecord.class));
    }
}
