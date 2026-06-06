package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.dto.AuditRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@Slf4j
public class ModelRoutingService {

    private final ChatLanguageModel classifierModel;
    private final AuditService auditService;

    @Setter
    @Value("${agent.routing.complexity-threshold:5}")
    private int complexityThreshold;

    @Setter
    @Value("${agent.routing.classifier-model}")
    private String classifierModelName;

    public ModelRoutingService(@Qualifier("classifierModel") ChatLanguageModel classifierModel, 
                               AuditService auditService) {
        this.classifierModel = classifierModel;
        this.auditService = auditService;
    }

    public boolean isComplexQuery(String query, String traceId) {
        String prompt = """
                Evaluate the complexity of the following user query regarding Amazon DynamoDB on a scale of 1 to 10.
                1-3: Simple, factual, or basic definition questions.
                4-6: Intermediate questions involving single-table design basics or specific API usage.
                7-10: Complex architectural, optimization, or multi-faceted design questions.
                
                Output ONLY the integer score.
                
                Query: %s
                """.formatted(query);

        long startTime = System.currentTimeMillis();
        try {
            Response<AiMessage> response = classifierModel.generate(Collections.singletonList(UserMessage.from(prompt)));
            long duration = System.currentTimeMillis() - startTime;

            String scoreText = response.content().text().trim();
            int score = Integer.parseInt(scoreText.replaceAll("[^0-9]", ""));
            
            log.info("Query complexity score: {} for query: '{}'", score, query);

            auditService.recordAudit(AuditRecord.builder()
                    .queryText(query)
                    .modelName(classifierModelName)
                    .inputTokens(response.tokenUsage().inputTokenCount())
                    .outputTokens(response.tokenUsage().outputTokenCount())
                    .totalLatencyMs(duration)
                    .traceId(traceId)
                    .build());

            return score > complexityThreshold;
        } catch (Exception e) {
            log.error("Error evaluating query complexity, defaulting to complex", e);
            return true;
        }
    }
}
