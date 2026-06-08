# Design Spec: Source-Aware Template & Structured Tool Logging

**Date**: 2026-06-07
**Topic**: Implementing a mandatory response template and capturing detailed tool execution payloads.
**Status**: Draft

## 1. Objective
Enhance the observability and transparency of the DDB Knowledge Agent. This involves:
1.  **Source Attribution**: Forcing the model to distinguish between official documentation and its own training data.
2.  **Tool Auditing**: Recording structured JSON payloads for every tool call (input arguments and output results) to understand exactly how the agent is interacting with the knowledge store.

## 2. Source-Aware Template Design
The requirement will be enforced through a **System Prompt Hard-Constraint** in the `QueryRoutingService`. 

### 2.1 The New System Prompt
```text
You are a DynamoDB expert. You have access to a tool to search the official documentation. You should use it to look up specific technical details.

You MUST format your entire response using the following mandatory Markdown template:

### 📚 From Official Documentation
[Provide information found ONLY using the search tool here. If the tool returned no results, state "No specific documentation found for this query."]

### 🧠 From Expert Knowledge
[Provide supplementary information from your own training data here to give a more complete or practical answer.]

---
**Constraint:** Do not hallucinate technical specifications. If there is a conflict, always prioritize information from the documentation tool.
```

## 3. Structured Tool Logging Design

### 3.1 Schema Update
We will add a `tool_calls` column to the `request_audit_logs` table to store execution details.

```sql
ALTER TABLE request_audit_logs ADD COLUMN tool_calls JSONB;
```

### 3.2 Audit Context Holder Update
The `AuditContext` will be updated to accumulate tool execution info during a multi-turn ReAct loop.
```java
public class AuditContext {
    private String traceId;
    private String capturedPrompt;
    private List<Map<String, Object>> toolExecutions; // Stores {name, args, result}
}
```

### 3.3 Listener Logic (`ChatModelAuditListener`)
- **`onResponse`**: If the model returns a `ToolExecutionRequest`, the listener will log the tool name and arguments.
- **`onRequest`**: If the request contains a `ToolExecutionResultMessage`, the listener will correlate it with the request and store the result in the `AuditContext`.

## 4. Implementation Details

### 4.1 Service Update (`QueryRoutingService.java`)
1.  Update the `systemPrompt` to use the new template.
2.  When recording the final audit, serialize the `toolExecutions` list from the `AuditContext` into JSON and pass it to the `AuditService`.

### 4.2 Audit Service Update (`AuditService.java`)
Update the SQL insert to handle the new `tool_calls` JSONB column.

## 5. Success Criteria
- Responses follow the Markdown header structure perfectly.
- The `request_audit_logs` table contains a JSON array of every tool called during the request.
- The logs show exactly what query was passed to the documentation tool and what text chunks were returned.
