package org.ai.agent.ddbknowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.config.PricingConfig;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final PricingConfig pricingConfig;

    @Async
    public void recordAudit(AuditRecord record) {
        log.info("Recording audit for model: {}. Tokens: In={}, Out={}", 
                record.getModelName(), record.getInputTokens(), record.getOutputTokens());
        
        PricingConfig.ModelPrice prices = findPricesForModel(record.getModelName());
        log.info("Found prices for model {}: InputPrice={}, OutputPrice={}", 
                record.getModelName(), prices.getInputPricePer1m(), prices.getOutputPricePer1m());

        BigDecimal inputCost = calculateCost(record.getInputTokens(), prices.getInputPricePer1m());
        BigDecimal outputCost = calculateCost(record.getOutputTokens(), prices.getOutputPricePer1m());
        BigDecimal totalCost = inputCost.add(outputCost);
        
        log.info("Calculated costs: Input={}, Output={}, Total={}", inputCost, outputCost, totalCost);

        String sql = """
            INSERT INTO request_audit_logs 
            (query_text, model_name, input_tokens, output_tokens, total_tokens, 
             input_cost, output_cost, total_cost, ttft_ms, total_latency_ms, is_cache_hit, trace_id, complexity_score)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
                record.getQueryText(),
                record.getModelName(),
                record.getInputTokens(),
                record.getOutputTokens(),
                record.getInputTokens() + record.getOutputTokens(),
                inputCost,
                outputCost,
                totalCost,
                record.getTtftMs(),
                record.getTotalLatencyMs(),
                record.isCacheHit(),
                record.getTraceId() != null ? java.util.UUID.fromString(record.getTraceId()) : null,
                record.getComplexityScore()
        );
    }

    private PricingConfig.ModelPrice findPricesForModel(String modelName) {
        if (pricingConfig.getModels() == null) {
            log.warn("PricingConfig.getModels() is NULL!");
            return new PricingConfig.ModelPrice();
        }
        if (pricingConfig.getModels().isEmpty()) {
            log.warn("PricingConfig.getModels() is EMPTY!");
            return new PricingConfig.ModelPrice();
        }
        if (modelName == null) {
            return new PricingConfig.ModelPrice();
        }

        // Sanitize the model name to match YAML keys (remove dots)
        String sanitizedModelName = modelName.replace(".", "");
        
        log.info("Available models in config: {}", pricingConfig.getModels().keySet());

        // 1. Exact match
        if (pricingConfig.getModels().containsKey(sanitizedModelName)) {
            return pricingConfig.getModels().get(sanitizedModelName);
        }

        // 2. Best prefix match
        return pricingConfig.getModels().entrySet().stream()
                .filter(entry -> sanitizedModelName.startsWith(entry.getKey()))
                .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                .map(java.util.Map.Entry::getValue)
                .orElse(new PricingConfig.ModelPrice());
    }

    private BigDecimal calculateCost(int tokens, double pricePer1m) {
        return BigDecimal.valueOf(tokens)
                .multiply(BigDecimal.valueOf(pricePer1m))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
    }
}
