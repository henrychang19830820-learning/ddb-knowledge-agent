package org.ai.agent.ddbknowledge.audit;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class ChatModelAuditListener implements ChatModelListener {

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        List<ChatMessage> messages = requestContext.request().messages();
        
        String formattedPrompt = messages.stream()
                .map(msg -> String.format("[%s]: %s", msg.type(), msg.text()))
                .collect(Collectors.joining("\n\n"));
        
        log.info("Captured high-fidelity prompt turn. Thread: {}. Prompt length: {}", 
                Thread.currentThread().getName(), formattedPrompt.length());
        AuditContextHolder.updateCapturedPrompt(formattedPrompt);

        // Capture tool results
        AuditContext context = AuditContextHolder.get();
        if (context != null) {
            for (ChatMessage message : messages) {
                if (message instanceof ToolExecutionResultMessage toolResult) {
                    context.getToolExecutions().stream()
                            .filter(exec -> toolResult.id().equals(exec.get("id")))
                            .findFirst()
                            .ifPresent(exec -> exec.put("result", toolResult.text()));
                }
            }
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        if (responseContext.response() != null && responseContext.response().aiMessage() != null) {
            List<ToolExecutionRequest> toolRequests = responseContext.response().aiMessage().toolExecutionRequests();
            if (toolRequests != null && !toolRequests.isEmpty()) {
                AuditContext context = AuditContextHolder.get();
                if (context != null) {
                    for (ToolExecutionRequest req : toolRequests) {
                        Map<String, Object> exec = new HashMap<>();
                        exec.put("id", req.id());
                        exec.put("name", req.name());
                        exec.put("arguments", req.arguments());
                        context.getToolExecutions().add(exec);
                    }
                }
            }
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {}
}
