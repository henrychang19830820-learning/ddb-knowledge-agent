package org.ai.agent.ddbknowledge.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AiServicesTest {

    interface Assistant {
        TokenStream chat(String message);
    }

    static class DocTool {
        @Tool("Search DynamoDB documentation")
        public String search(String query) {
            System.out.println("TOOL CALLED WITH: " + query);
            return "DynamoDB is a NoSQL database.";
        }
    }

    @Test
    public void test() throws Exception {
        String key = System.getenv("GOOGLE_API_KEY");
        if (key == null) return;

        GoogleAiGeminiStreamingChatModel model = GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(key)
                .modelName("gemini-3.5-flash")
                .build();

        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .tools(new DocTool())
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        System.out.println("Starting chat...");
        assistant.chat("What is DynamoDB?")
                .onNext(System.out::print)
                .onComplete(r -> {
                    System.out.println("\nComplete! Tokens: " + r.tokenUsage());
                    latch.countDown();
                })
                .onError(e -> {
                    e.printStackTrace();
                    latch.countDown();
                });

        latch.await(30, TimeUnit.SECONDS);
    }
}
