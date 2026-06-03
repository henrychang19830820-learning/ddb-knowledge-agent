package org.ai.agent.ddbknowledge.web;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ai.agent.ddbknowledge.service.QueryRoutingService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@Slf4j
public class QueryController {

    private final QueryRoutingService queryRoutingService;
    private final org.ai.agent.ddbknowledge.service.IngestionService ingestionService;

    @org.springframework.beans.factory.annotation.Value("${agent.ingest.docs-path}")
    private String docsPath;

    @org.springframework.beans.factory.annotation.Value("${agent.ingest.chunking-strategy:recursive-1000-200}")
    private String chunkingStrategy;

    @PostMapping("/ingest")
    public String ingest() {
        log.info("Manual ingestion triggered via API");
        try {
            ingestionService.ingest(docsPath, chunkingStrategy);
            return "Ingestion completed successfully";
        } catch (Exception e) {
            log.error("Manual ingestion failed: {}", e.getMessage(), e);
            return "Ingestion failed: " + e.getMessage();
        }
    }

    @DeleteMapping("/docs")
    public String clearDocs() {
        log.info("Clear documents triggered via API");
        try {
            ingestionService.removeAll();
            return "Knowledge base cleared successfully";
        } catch (Exception e) {
            log.error("Clear documents failed: {}", e.getMessage(), e);
            return "Clear documents failed: " + e.getMessage();
        }
    }

    @DeleteMapping("/cache")
    public String clearCache() {
        log.info("Clear cache triggered via API");
        try {
            queryRoutingService.clearCache();
            return "Semantic cache cleared successfully";
        } catch (Exception e) {
            log.error("Clear cache failed: {}", e.getMessage(), e);
            return "Clear cache failed: " + e.getMessage();
        }
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String question) {
        return queryRoutingService.ask(question);
    }

    @GetMapping("/ask-stream")
    public SseEmitter askStream(@RequestParam String question) {
        SseEmitter emitter = new SseEmitter(60000L); // 1 minute timeout
        
        queryRoutingService.askStreaming(question, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                log.debug("Streaming token: {}", token);
                try {
                    emitter.send(SseEmitter.event()
                            .name("token")
                            .data(token));
                } catch (java.io.IOException e) {
                    log.error("Failed to send token", e);
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("complete")
                            .data(""));
                    emitter.complete();
                } catch (java.io.IOException e) {
                    log.error("Failed to complete stream", e);
                }
            }

            @Override
            public void onError(Throwable error) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(error.getMessage()));
                    emitter.completeWithError(error);
                } catch (java.io.IOException e) {
                    log.error("Failed to send error", e);
                }
            }
        });
        
        return emitter;
    }
}
