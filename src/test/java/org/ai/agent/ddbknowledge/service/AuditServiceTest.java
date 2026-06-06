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
                eq(false)
        );
    }
}
