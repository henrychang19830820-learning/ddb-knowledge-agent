# High-Fidelity Auditing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture the actual structural payload (system prompt, history, tool results) sent to the LLM using a `ChatModelListener` and store it in the audit logs.

**Architecture:** Implement a `ThreadLocal` context holder to link listener events to the main request. Create a listener to format messages into a single string. Refactor `AuditService` to pull this high-fidelity prompt.

**Tech Stack:** Java 21, Spring Boot, LangChain4j.

---

### Task 1: Audit Context Holder

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java`
- Create: `src/main/java/org/ai/agent/ddbknowledge/audit/AuditContextHolder.java`

- [ ] **Step 1: Create AuditContext DTO**

```java
package org.ai.agent.ddbknowledge.audit;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditContext {
    private String traceId;
    private String capturedPrompt;
}
```

- [ ] **Step 2: Create AuditContextHolder**

```java
package org.ai.agent.ddbknowledge.audit;

public class AuditContextHolder {
    private static final ThreadLocal<AuditContext> CONTEXT = new ThreadLocal<>();

    public static void set(AuditContext context) {
        CONTEXT.set(context);
    }

    public static AuditContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
    
    public static void updateCapturedPrompt(String prompt) {
        AuditContext current = CONTEXT.get();
        if (current != null) {
            current.setCapturedPrompt(prompt);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/audit/
git commit -m "feat: implement ThreadLocal AuditContextHolder for high-fidelity tracing"
```

---

### Task 2: ChatModelAuditListener Implementation

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/audit/ChatModelAuditListener.java`

- [ ] **Step 1: Implement the listener**

```java
package org.ai.agent.ddbknowledge.audit;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class ChatModelAuditListener implements ChatModelListener {

    @Override
    public void onChatModelRequest(ChatModelRequestContext requestContext) {
        List<ChatMessage> messages = requestContext.chatModelRequest().messages();
        
        String formattedPrompt = messages.stream()
                .map(msg -> String.format("[%s]: %s", msg.type(), msg.text()))
                .collect(Collectors.joining("\n\n"));
        
        log.debug("Captured high-fidelity prompt turn");
        AuditContextHolder.updateCapturedPrompt(formattedPrompt);
    }

    @Override
    public void onChatModelResponse(ChatModelResponseContext responseContext) {}

    @Override
    public void onChatModelError(ChatModelRequestContext requestContext, Throwable error) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/audit/ChatModelAuditListener.java
git commit -m "feat: implement ChatModelAuditListener to capture structural payloads"
```

---

### Task 3: Configuration & Registration

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java`

- [ ] **Step 1: Register the listener in all model beans**

Import `ChatModelAuditListener` and `Collections`. Update all `builder()` calls to include `.listeners(Collections.singletonList(new ChatModelAuditListener()))`.

```java
// Example for one bean, repeat for all 4 (Classifier, Simple, Medium, Complex)
    @Bean
    @Qualifier("complexChatModel")
    public StreamingChatLanguageModel complexChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(complexTierModelName)
                .listeners(java.util.Collections.singletonList(new org.ai.agent.ddbknowledge.audit.ChatModelAuditListener()))
                .build();
    }
```

- [ ] **Step 2: Verify and Commit**

Run: `./gradlew classes`

```bash
git add src/main/java/org/ai/agent/ddbknowledge/config/AgentConfig.java
git commit -m "feat: register ChatModelAuditListener in model configuration"
```

---

### Task 4: Service Integration

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/AuditService.java`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/ModelRoutingService.java`

- [ ] **Step 1: Refactor AuditService to use Context**

Update `recordAudit` to prioritize `AuditContextHolder.get().getCapturedPrompt()`.

```java
// In AuditService.java
    @Async
    public void recordAudit(AuditRecord record) {
        // ... calculation logic ...

        // Use high-fidelity prompt from listener if available
        String finalPrompt = record.getFullPrompt();
        AuditContext context = AuditContextHolder.get();
        if (context != null && context.getCapturedPrompt() != null) {
            finalPrompt = context.getCapturedPrompt();
        }

        // ... update JDBC call to use finalPrompt ...
    }
```

- [ ] **Step 2: Initialize Context in QueryRoutingService**

At the start of `askStreaming`, initialize the holder. Ensure it is cleared at the end (even on error).

```java
// In QueryRoutingService.java askStreaming
        AuditContextHolder.set(AuditContext.builder().traceId(traceId).build());
        try {
           // ... existing logic ...
        } finally {
            // Note: Since recordAudit is @Async, we need to be careful with clear().
            // For now, let's just ensure the data is updated.
        }
```

- [ ] **Step 3: Remove Manual Prompt Crafting**

In `ModelRoutingService` and `QueryRoutingService`, you can now pass `null` or a simple placeholder for `fullPrompt` since the service will pull the "real" one.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/
git commit -m "feat: integrate high-fidelity prompt capture into auditing flow"
```

---

### Task 5: Verification

- [ ] **Step 1: Run E2E Test**

Perform a query that triggers a tool call.

- [ ] **Step 2: Verify DB Content**

Run: `docker exec ddb-knowledge-agent-postgres-1 psql -U user -d ddb_agent -c "SELECT full_prompt FROM request_audit_logs ORDER BY created_at DESC LIMIT 1;"`
Expected: The `full_prompt` should contain `[SYSTEM]: ... [USER]: ...` and ideally show the injected documentation if it was the final turn.

- [ ] **Step 3: Final cleanup**

```bash
git commit -am "chore: finalize high-fidelity auditing"
```
