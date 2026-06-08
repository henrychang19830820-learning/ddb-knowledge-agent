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
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {}

    @Override
    public void onError(ChatModelErrorContext errorContext) {}
}
