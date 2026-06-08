# Design Spec: High-Fidelity Auditing with ChatModelListener

**Date**: 2026-06-07
**Topic**: Capturing exact raw prompts used by the LLM via observability listeners.
**Status**: Draft

## 1. Objective
Replace manually crafted prompt logging with high-fidelity capture of the actual structural payload sent to the LLM. This ensures that in complex ReAct loops, we see exactly what context, tool results, and history were provided to the model.

## 2. Architecture Overview
The system will use a **Listener Pattern** to intercept low-level model requests.

- **`AuditContextHolder`**: A `ThreadLocal` storage for the current request's `trace_id` and the `full_prompt` captured by the listener.
- **`ChatModelAuditListener`**: Implements LangChain4j's `ChatModelListener`. It formats the `List<ChatMessage>` from each request into a human-readable string and stores it in the context holder.
- **`AuditService`**: Refactored to automatically pull the prompt from `AuditContextHolder` if the provided prompt is null or generic.

## 3. Component Details

### 3.1 `AuditContextHolder` (New)
A utility to ensure the listener and the service (running in the same thread) can share data without direct dependency.
```java
public class AuditContextHolder {
    private static final ThreadLocal<AuditContext> context = new ThreadLocal<>();
    // Methods: setTraceId, setComplexity, setCapturedPrompt, etc.
}
```

### 3.2 `ChatModelAuditListener` (New)
Registered in `AgentConfig` for all model beans.
- **`onChatModelRequest`**: 
    1. Extracts `List<ChatMessage>`.
    2. Formats as: `[SYSTEM]... [USER]... [TOOL_EXECUTION]...`.
    3. Updates `AuditContextHolder`.

### 3.3 Refactored `AuditService`
The `recordAudit` method will prioritize the listener-captured prompt over the "manually crafted" one, ensuring maximum accuracy for ReAct logs.

## 4. Implementation Roadmap

### Phase 1: Context & Listener
- Implement `AuditContextHolder` and `AuditContext` DTO.
- Implement `ChatModelAuditListener` with message formatting logic.

### Phase 2: Configuration
- Register the listener in `AgentConfig` for Classifier, Simple, Medium, and Complex models.

### Phase 3: Integration
- Update `QueryRoutingService` to initialize the `AuditContextHolder` at the start of each request.
- Update `AuditService` to pull data from the holder.

## 5. Success Criteria
- Audit logs contain the **actual** messages sent in the final reasoning turn.
- Tool results (from `DocumentationTool`) are visible in the `full_prompt` column.
- No impact on user-perceived streaming latency.
