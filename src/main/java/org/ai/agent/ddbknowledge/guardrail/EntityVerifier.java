package org.ai.agent.ddbknowledge.guardrail;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class EntityVerifier {
    private final ChatLanguageModel classifierModel;

    public EntityVerifier(@Qualifier("classifierModel") ChatLanguageModel classifierModel) {
        this.classifierModel = classifierModel;
    }

    public boolean areEquivalent(String query1, String query2) {
        String prompt = """
            You are a technical validator for Amazon DynamoDB.
            Compare the two user queries below.
            Determine if they target the same specific API, feature, index type (GSI vs LSI), or technical constraint.
            If they ask about different things (e.g., one about GSI and another about LSI), they are NOT equivalent.
            If they use different words for the same thing (e.g., "partition key" vs "PK"), they ARE equivalent.

            Query 1: %s
            Query 2: %s

            Output ONLY "EQUIVALENT" or "DIFFERENT".
            """.formatted(query1, query2);

        String result = classifierModel.generate(prompt).trim();
        return "EQUIVALENT".equalsIgnoreCase(result);
    }
}
