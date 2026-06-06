package org.ai.agent.ddbknowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "agent")
@Data
public class PricingConfig {

    private Map<String, ModelPrice> pricing = new HashMap<>();

    @Data
    public static class ModelPrice {
        private double inputPricePer1m;
        private double outputPricePer1m;
    }

    public Map<String, ModelPrice> getModels() {
        return pricing;
    }
}
