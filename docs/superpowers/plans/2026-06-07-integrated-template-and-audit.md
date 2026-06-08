# Implement Source-Aware Template and Final Audit Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update the system prompt to use a mandatory Markdown template for source-aware responses and integrate tool execution auditing by serializing `AuditContext` data into the audit log.

**Architecture:** Modify `QueryRoutingService` to use a more structured system prompt and serialize tool execution logs using Jackson's `ObjectMapper` during the audit recording phase of the request lifecycle.

**Tech Stack:** Java, Spring Boot, LangChain4j, Jackson.

---

### Task 1: Update System Prompt Template

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Update the `systemPrompt` variable**

Update the `systemPrompt` string to include the new Markdown template as specified in the requirements.

```java
                String systemPrompt = "You are a DynamoDB expert. You have access to a tool to search the official documentation. You should use it to look up specific technical details.\n" +
                        "\n" +
                        "You MUST format your entire response using the following mandatory Markdown template:\n" +
                        "\n" +
                        "### 📚 From Official Documentation\n" +
                        "[Provide information found ONLY using the search tool here. If the tool returned no results, state \"No specific documentation found for this query.\"]\n" +
                        "\n" +
                        "### 🧠 From Expert Knowledge\n" +
                        "[Provide supplementary information from your own training data here to give a more complete or practical answer.]\n" +
                        "\n" +
                        "---\n" +
                        "**Constraint:** Do not hallucinate technical specifications. If there is a conflict, always prioritize information from the documentation tool.";
```

### Task 2: Integrate Tool Auditing in `onComplete`

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Add JSON serialization of tool executions in `onComplete`**

Use `ObjectMapper` to serialize `auditContext.getToolExecutions()` and pass it to the `AuditRecord` builder.

```java
                        .onComplete(response -> {
                            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                            log.info("TokenStream completed [traceId={}] in {}ms", traceId, totalLatency);
                            
                            // Access the context via final local variable
                            String finalHighFidelityPrompt = auditContext.getCapturedPrompt();

                            // Serialize tool executions
                            String toolCallsJson = null;
                            try {
                                toolCallsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(auditContext.getToolExecutions());
                            } catch (Exception e) {
                                log.warn("Failed to serialize tool calls for auditing [traceId={}]", traceId, e);
                            }

                            int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
                            int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

                            auditService.recordAudit(AuditRecord.builder()
                                    .queryText(query)
                                    .fullPrompt(finalHighFidelityPrompt)
                                    .modelName(selectedModelName)
                                    .inputTokens(inputTokens)
                                    .outputTokens(outputTokens)
                                    .ttftMs(ttft[0])
                                    .totalLatencyMs(totalLatency)
                                    .isCacheHit(false)
                                    .traceId(traceId)
                                    .complexityScore(complexityScore)
                                    .toolCallsJson(toolCallsJson) // Add this
                                    .build());
```

### Task 3: Integrate Tool Auditing in `onError`

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

- [ ] **Step 1: Add JSON serialization of tool executions in `onError`**

Apply the same serialization logic to the `onError` callback.

```java
                        .onError(error -> {
                            log.error("Native Streaming Error [traceId={}]", traceId, error);
                            long totalLatency = (System.nanoTime() - startTime) / 1_000_000;
                            
                            String finalHighFidelityPrompt = auditContext.getCapturedPrompt();

                            // Serialize tool executions
                            String toolCallsJson = null;
                            try {
                                toolCallsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(auditContext.getToolExecutions());
                            } catch (Exception e) {
                                log.warn("Failed to serialize tool calls for auditing [traceId={}]", traceId, e);
                            }

                            auditService.recordAudit(AuditRecord.builder()
                                    .queryText(query)
                                    .fullPrompt(finalHighFidelityPrompt)
                                    .modelName(selectedModelName)
                                    .inputTokens(0)
                                    .outputTokens(0)
                                    .ttftMs(ttft[0])
                                    .totalLatencyMs(totalLatency)
                                    .isCacheHit(false)
                                    .traceId(traceId)
                                    .complexityScore(complexityScore)
                                    .toolCallsJson(toolCallsJson) // Add this
                                    .build());
                            handler.onError(error);
                        })
```

### Task 4: Verification

- [ ] **Step 1: Compile the project**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

### Task 5: Final Commit

- [ ] **Step 1: Commit the changes**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: implement source-aware template and integrate tool auditing"
```
