package org.ai.agent.ddbknowledge.service;

import org.ai.agent.ddbknowledge.config.PricingConfig;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.Map;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PricingConfig pricingConfig;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        pricingConfig = mock(PricingConfig.class);
        // This will fail to compile initially because AuditService doesn't exist
        auditService = new AuditService(jdbcTemplate, pricingConfig);
    }

    @Test
    void testRecordAudit_CalculatesCostAndInserts() {
        // Setup mock pricing
        PricingConfig.ModelPrice price = new PricingConfig.ModelPrice();
        price.setInputPricePer1m(0.10);
        price.setOutputPricePer1m(0.40);
        when(pricingConfig.getModels()).thenReturn(Map.of("test-model", price));

        AuditRecord record = AuditRecord.builder()
                .queryText("Test query")
                .modelName("test-model")
                .inputTokens(1500)
                .outputTokens(500)
                .ttftMs(150)
                .totalLatencyMs(200)
                .isCacheHit(false)
                .build();

        // Execute
        auditService.recordAudit(record);

        // Verify JDBC call
        // Expected input cost: 1500 * 0.10 / 1M = 0.00015
        // Expected output cost: 500 * 0.40 / 1M = 0.00020
        // Expected total cost: 0.00035
        BigDecimal expectedInputCost = new BigDecimal("0.0001500000");
        BigDecimal expectedOutputCost = new BigDecimal("0.0002000000");
        BigDecimal expectedTotalCost = new BigDecimal("0.0003500000");

        verify(jdbcTemplate).update(
                anyString(),
                eq("Test query"),
                eq("test-model"),
                eq(1500),
                eq(500),
                eq(2000), // total tokens
                eq(expectedInputCost),
                eq(expectedOutputCost),
                eq(expectedTotalCost),
                eq(150L),
                eq(200L),
                eq(false),
                isNull(),
                isNull() // complexity_score
        );
        }

        @Test
        void testRecordAudit_IncludesTraceId() {
        // Setup mock pricing
        PricingConfig.ModelPrice price = new PricingConfig.ModelPrice();
        price.setInputPricePer1m(0.10);
        price.setOutputPricePer1m(0.40);
        when(pricingConfig.getModels()).thenReturn(Map.of("test-model", price));

        String traceId = "550e8400-e29b-41d4-a716-446655440000";
        AuditRecord record = AuditRecord.builder()
                .queryText("Test query")
                .modelName("test-model")
                .inputTokens(1000)
                .outputTokens(1000)
                .traceId(traceId)
                .build();

        // Execute
        auditService.recordAudit(record);

        // Verify JDBC call includes trace_id
        verify(jdbcTemplate).update(
                contains("trace_id"),
                eq("Test query"),
                eq("test-model"),
                eq(1000),
                eq(1000),
                eq(2000),
                any(BigDecimal.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyLong(),
                anyLong(),
                anyBoolean(),
                eq(java.util.UUID.fromString(traceId)),
                isNull() // complexity_score
        );
        }

        @Test
        void testPrefixModelMatching() {
        // Setup mock pricing for base model (sanitized key)
        PricingConfig.ModelPrice price = new PricingConfig.ModelPrice();
        price.setInputPricePer1m(0.10);
        price.setOutputPricePer1m(0.40);
        when(pricingConfig.getModels()).thenReturn(Map.of("gemini-31-flash-lite", price));

        // Record audit for a preview variation
        AuditRecord record = AuditRecord.builder()
                .queryText("Prefix query")
                .modelName("gemini-3.1-flash-lite-preview")
                .inputTokens(1000)
                .outputTokens(1000)
                .build();

        auditService.recordAudit(record);

        // Expected cost: 1000 * 0.10 / 1M = 0.0001
        BigDecimal expectedInputCost = new BigDecimal("0.0001000000");

        verify(jdbcTemplate).update(
                anyString(),
                eq("Prefix query"),
                eq("gemini-3.1-flash-lite-preview"),
                eq(1000),
                eq(1000),
                eq(2000),
                eq(expectedInputCost),
                any(),
                any(),
                anyLong(),
                anyLong(),
                anyBoolean(),
                isNull(),
                isNull() // complexity_score
        );
        }

        }
