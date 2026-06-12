# Source-Doc Links in Answers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Append a clickable **Sources** footer to every documentation-backed answer, marking each retrieved doc as cited or retrieved, and serve the raw markdown of those docs from a new local endpoint.

**Architecture:** The `DocumentationTool` records each retrieved chunk's `file_name` into the per-request `AuditContext` and tags chunk text with `[Source: <file>]` so the model can cite it. When the model stream completes, `QueryRoutingService` asks a new pure `SourceCitationFormatter` to build a markdown footer (cited vs. retrieved), streams it to the client, and appends it to the cached answer. A new `SourceController` serves the raw `.md` files at `/sources/<file>` with path-traversal protection.

**Tech Stack:** Java 21, Spring Boot 3.3, LangChain4j 0.36.2, Gradle, JUnit 5 + Mockito.

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java` | Per-request carrier; add ordered de-duping source set | Modify |
| `src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java` | Record `file_name`s, tag chunk text with `[Source: ...]` | Modify |
| `src/main/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatter.java` | Pure markdown footer builder (cited vs. retrieved) | Create |
| `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java` | System prompt instruction + stream/append footer on complete | Modify |
| `src/main/java/org/ai/agent/ddbknowledge/web/SourceController.java` | `GET /sources/{filename}` raw `.md` server, path-safe | Create |
| `src/test/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatterTest.java` | Unit tests for footer logic | Create |
| `src/test/java/org/ai/agent/ddbknowledge/tool/DocumentationToolTest.java` | Extend: source recording + tagging | Modify |
| `src/test/java/org/ai/agent/ddbknowledge/web/SourceControllerTest.java` | Endpoint + path-traversal tests | Create |
| `README.md` | Document the Sources footer + `/sources/` endpoint | Modify |

**Build/test commands:** `./gradlew test` (all), `./gradlew test --tests "FQCN"` (single class).

---

## Task 1: AuditContext carries retrieved sources

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java`

The current class is a Lombok `@Data @Builder` holding `traceId`, `capturedPrompt`, and `toolExecutions`. Add an insertion-ordered, de-duplicating `Set<String>` plus a null/blank-safe adder. `@Data` auto-generates `getRetrievedSources()`.

- [ ] **Step 1: Add the field and adder method**

Open `AuditContext.java`. Add imports near the existing `java.util` imports:

```java
import java.util.LinkedHashSet;
import java.util.Set;
```

Add the field after `toolExecutions` (keep the `@Builder.Default` so the builder path also initializes it):

```java
    @Builder.Default
    private Set<String> retrievedSources = new LinkedHashSet<>();
```

Add this method inside the class, after `addToolExecution(...)`:

```java
    public void addRetrievedSource(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        retrievedSources.add(fileName);
    }
```

- [ ] **Step 2: Compile to verify it builds**

Run: `./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL (no errors). Lombok generates `getRetrievedSources()`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/audit/AuditContext.java
git commit -m "feat: add retrievedSources set to AuditContext"
```

---

## Task 2: SourceCitationFormatter builds the footer

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatter.java`
- Test: `src/test/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatterTest.java`

A pure, stateless `@Component`. Input: the retrieved-sources set + the model's response text. Output: a markdown footer, or `""` when the set is empty. A source is **cited** if its exact `file_name` appears anywhere in the response text.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatterTest.java`:

```java
package org.ai.agent.ddbknowledge.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCitationFormatterTest {

    private final SourceCitationFormatter formatter = new SourceCitationFormatter();

    @Test
    void emptySetReturnsEmptyString() {
        assertEquals("", formatter.format(new LinkedHashSet<>(), "any response"));
    }

    @Test
    void citedWhenFilenameAppearsInResponse() {
        Set<String> sources = new LinkedHashSet<>();
        sources.add("WorkingWithDynamo.md");

        String footer = formatter.format(sources, "See WorkingWithDynamo.md for details.");

        assertTrue(footer.contains("- ✅ [WorkingWithDynamo.md](/sources/WorkingWithDynamo.md) — cited"));
    }

    @Test
    void retrievedWhenFilenameAbsentFromResponse() {
        Set<String> sources = new LinkedHashSet<>();
        sources.add("GettingStarted.md");

        String footer = formatter.format(sources, "Some answer with no filename.");

        assertTrue(footer.contains("- 📄 [GettingStarted.md](/sources/GettingStarted.md) — retrieved"));
    }

    @Test
    void includesHeaderAndPreservesInsertionOrder() {
        Set<String> sources = new LinkedHashSet<>();
        sources.add("First.md");
        sources.add("Second.md");

        String footer = formatter.format(sources, "no citations here");

        assertTrue(footer.startsWith("\n\n---\n**Sources:**\n"));
        assertTrue(footer.indexOf("First.md") < footer.indexOf("Second.md"));
    }

    @Test
    void nullResponseTextMarksAllRetrieved() {
        Set<String> sources = new LinkedHashSet<>();
        sources.add("Doc.md");

        String footer = formatter.format(sources, null);

        assertTrue(footer.contains("— retrieved"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "org.ai.agent.ddbknowledge.service.SourceCitationFormatterTest"`
Expected: FAIL — compilation error, `SourceCitationFormatter` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatter.java`:

```java
package org.ai.agent.ddbknowledge.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Pure, stateless builder for the "Sources" footer appended to documentation-backed answers.
 * Holds no state and calls nothing external — just string assembly.
 */
@Component
public class SourceCitationFormatter {

    /**
     * @param retrievedSources de-duplicated file names in first-retrieved order
     * @param responseText     the model's answer; a source is "cited" if its file name appears here
     * @return the markdown footer, or "" when there are no retrieved sources
     */
    public String format(Set<String> retrievedSources, String responseText) {
        if (retrievedSources == null || retrievedSources.isEmpty()) {
            return "";
        }

        StringBuilder footer = new StringBuilder("\n\n---\n**Sources:**\n");
        for (String fileName : retrievedSources) {
            boolean cited = responseText != null && responseText.contains(fileName);
            String icon = cited ? "✅" : "📄";
            String label = cited ? "cited" : "retrieved";
            footer.append("- ")
                  .append(icon)
                  .append(" [")
                  .append(fileName)
                  .append("](/sources/")
                  .append(fileName)
                  .append(") — ")
                  .append(label)
                  .append("\n");
        }
        return footer.toString();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "org.ai.agent.ddbknowledge.service.SourceCitationFormatterTest"`
Expected: PASS — 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatter.java src/test/java/org/ai/agent/ddbknowledge/service/SourceCitationFormatterTest.java
git commit -m "feat: add SourceCitationFormatter for Sources footer"
```

---

## Task 3: DocumentationTool records and tags sources

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java`
- Test: `src/test/java/org/ai/agent/ddbknowledge/tool/DocumentationToolTest.java`

For each match, read `file_name` from the segment metadata, record it into the `AuditContext`, and prefix the chunk text with `[Source: <file_name>]\n` so the model can cite it. When `file_name` is absent (null/blank), skip the tag and skip recording. The "no results" branch is unchanged.

Note: `match.embedded().metadata().getString("file_name")` returns `null` when the key is absent — that's the missing-metadata path. The existing test uses `TextSegment.from("Document result")` (no metadata), which must remain valid.

- [ ] **Step 1: Write the failing tests (extend the existing test class)**

Replace the entire contents of `src/test/java/org/ai/agent/ddbknowledge/tool/DocumentationToolTest.java` with:

```java
package org.ai.agent.ddbknowledge.tool;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.ai.agent.ddbknowledge.audit.AuditContext;
import org.ai.agent.ddbknowledge.audit.AuditContextHolder;
import org.ai.agent.ddbknowledge.service.HybridSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentationToolTest {

    @Mock
    private HybridSearchService hybridSearchService;

    @InjectMocks
    private DocumentationTool tool;

    @AfterEach
    void clearContext() {
        AuditContextHolder.clear();
    }

    @Test
    void testSearchDocumentation() {
        when(hybridSearchService.search("test query", 5)).thenReturn(
            List.of(new EmbeddingMatch<>(0.9, "1", null, TextSegment.from("Document result")))
        );

        String result = tool.searchDocumentation("test query");

        assertTrue(result.contains("Document result"));
        verify(hybridSearchService).search("test query", 5);
    }

    @Test
    void recordsFileNameIntoAuditContextAndTagsChunk() {
        AuditContext context = AuditContext.builder().traceId("t1").build();
        AuditContextHolder.set(context);

        TextSegment segment = TextSegment.from(
            "Chunk body",
            Metadata.from(Map.of("file_name", "WorkingWithDynamo.md"))
        );
        when(hybridSearchService.search("dynamo", 5)).thenReturn(
            List.of(new EmbeddingMatch<>(0.9, "1", null, segment))
        );

        String result = tool.searchDocumentation("dynamo");

        assertTrue(context.getRetrievedSources().contains("WorkingWithDynamo.md"));
        assertTrue(result.contains("[Source: WorkingWithDynamo.md]"));
        assertTrue(result.contains("Chunk body"));
    }

    @Test
    void skipsChunkWithoutFileName() {
        AuditContext context = AuditContext.builder().traceId("t2").build();
        AuditContextHolder.set(context);

        when(hybridSearchService.search("nometa", 5)).thenReturn(
            List.of(new EmbeddingMatch<>(0.9, "1", null, TextSegment.from("Untagged body")))
        );

        String result = tool.searchDocumentation("nometa");

        assertTrue(context.getRetrievedSources().isEmpty());
        assertFalse(result.contains("[Source:"));
        assertTrue(result.contains("Untagged body"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "org.ai.agent.ddbknowledge.tool.DocumentationToolTest"`
Expected: FAIL — `recordsFileNameIntoAuditContextAndTagsChunk` and `skipsChunkWithoutFileName` fail because the tool currently discards metadata and does not tag chunks.

- [ ] **Step 3: Update the implementation**

In `src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java`, replace the `else` block that builds `result` (the `matches.stream()...collect(...)` chain) and fold the source recording into the same loop. Replace lines 27–42 (from `String result;` through `return result;`) with:

```java
        String result;
        org.ai.agent.ddbknowledge.audit.AuditContext context =
                org.ai.agent.ddbknowledge.audit.AuditContextHolder.get();

        if (matches.isEmpty()) {
            result = "No relevant documentation found for the query.";
        } else {
            result = matches.stream()
                    .map(match -> {
                        String fileName = match.embedded().metadata().getString("file_name");
                        String text = match.embedded().text();
                        if (fileName == null || fileName.isBlank()) {
                            return text;
                        }
                        if (context != null) {
                            context.addRetrievedSource(fileName);
                        }
                        return "[Source: " + fileName + "]\n" + text;
                    })
                    .collect(Collectors.joining("\n\n"));
        }

        // Directly record execution to AuditContext for high-fidelity logs
        if (context != null) {
            context.addToolExecution("searchDocumentation", query, result);
        }

        return result;
```

Note: this removes the old local re-fetch of `context` inside the audit block (we now fetch `context` once at the top). Confirm there is no second `AuditContextHolder.get()` left in the method.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.ai.agent.ddbknowledge.tool.DocumentationToolTest"`
Expected: PASS — all 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/tool/DocumentationTool.java src/test/java/org/ai/agent/ddbknowledge/tool/DocumentationToolTest.java
git commit -m "feat: record and tag source file names in DocumentationTool"
```

---

## Task 4: SourceController serves raw docs with path-traversal protection

**Files:**
- Create: `src/main/java/org/ai/agent/ddbknowledge/web/SourceController.java`
- Test: `src/test/java/org/ai/agent/ddbknowledge/web/SourceControllerTest.java`

`GET /sources/{filename}` returns the raw markdown as `text/plain;charset=UTF-8` (inline browser display). Reject any filename containing `/`, `\`, or `..`, or that is not a simple `*.md` name → `400`. Resolve against the docs dir, normalize, and verify the parent is still the docs dir before reading. Missing/unreadable file → `404`.

The controller reads the docs directory from `${agent.ingest.docs-path}` (default `local_test_docs`), the same property `QueryController` uses.

The test injects a temp directory into the `docsPath` field via reflection (`ReflectionTestUtils`) and calls the controller method directly — no Spring context needed, keeping it a fast unit test.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/ai/agent/ddbknowledge/web/SourceControllerTest.java`:

```java
package org.ai.agent.ddbknowledge.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceControllerTest {

    private SourceController controllerFor(Path docsDir) {
        SourceController controller = new SourceController();
        ReflectionTestUtils.setField(controller, "docsPath", docsDir.toString());
        return controller;
    }

    @Test
    void servesExistingMarkdownAsPlainText(@TempDir Path docsDir) throws Exception {
        Files.writeString(docsDir.resolve("WorkingWithDynamo.md"), "# Title\nBody");
        SourceController controller = controllerFor(docsDir);

        ResponseEntity<String> response = controller.getSource("WorkingWithDynamo.md");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("# Title\nBody", response.getBody());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("text/plain"));
    }

    @Test
    void missingFileReturns404(@TempDir Path docsDir) {
        SourceController controller = controllerFor(docsDir);

        ResponseEntity<String> response = controller.getSource("DoesNotExist.md");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void rejectsTraversalWithDotDot(@TempDir Path docsDir) {
        SourceController controller = controllerFor(docsDir);

        ResponseEntity<String> response = controller.getSource("..%2Fsecret.md");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void rejectsNonMarkdownName(@TempDir Path docsDir) {
        SourceController controller = controllerFor(docsDir);

        ResponseEntity<String> response = controller.getSource("config.yml");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void rejectsNameWithSlash(@TempDir Path docsDir) {
        SourceController controller = controllerFor(docsDir);

        ResponseEntity<String> response = controller.getSource("sub/dir.md");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "org.ai.agent.ddbknowledge.web.SourceControllerTest"`
Expected: FAIL — compilation error, `SourceController` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/ai/agent/ddbknowledge/web/SourceController.java`:

```java
package org.ai.agent.ddbknowledge.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@Slf4j
public class SourceController {

    @Value("${agent.ingest.docs-path}")
    private String docsPath;

    @GetMapping(value = "/sources/{filename}", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> getSource(@PathVariable String filename) {
        // Reject anything that is not a simple *.md name.
        if (filename == null
                || filename.contains("/")
                || filename.contains("\\")
                || filename.contains("..")
                || !filename.endsWith(".md")
                || filename.length() <= ".md".length()) {
            log.warn("Rejected unsafe source request: {}", filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        Path docsDir = Paths.get(docsPath).toAbsolutePath().normalize();
        Path target = docsDir.resolve(filename).normalize();

        // Defense-in-depth: ensure the resolved path stays inside the docs dir.
        if (!target.startsWith(docsDir) || target.getParent() == null
                || !target.getParent().equals(docsDir)) {
            log.warn("Rejected out-of-bounds source request: {}", filename);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (!Files.isReadable(target) || !Files.isRegularFile(target)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body(content);
        } catch (IOException e) {
            log.error("Failed to read source file {}", filename, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}
```

Note on the traversal test: `..%2Fsecret.md` reaches the handler decoded as `../secret.md` (Spring decodes path variables), which contains both `..` and `/` → rejected. The literal-string check is the guard under test.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.ai.agent.ddbknowledge.web.SourceControllerTest"`
Expected: PASS — all 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/web/SourceController.java src/test/java/org/ai/agent/ddbknowledge/web/SourceControllerTest.java
git commit -m "feat: add /sources/{filename} endpoint with path-traversal protection"
```

---

## Task 5: QueryRoutingService streams and caches the footer

**Files:**
- Modify: `src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java`

Three changes: (1) inject `SourceCitationFormatter`; (2) add a system-prompt instruction telling the model to reference source filenames; (3) in `onComplete`, build the footer, stream it, append it to `fullResponse` before the cache write so cached answers carry it.

This service has no unit test today and is hard to unit-test (spawns a thread, calls live models). Verify via full compile + the rest of the suite; the footer logic itself is already covered by `SourceCitationFormatterTest`.

- [ ] **Step 1: Inject the formatter**

In `QueryRoutingService.java`, add the field after `private final EntityMatcher entityMatcher;` (around line 42):

```java
    private final SourceCitationFormatter sourceCitationFormatter;
```

Add the constructor parameter at the end of the parameter list (after `EntityMatcher entityMatcher`) — update both the signature and the assignment:

```java
                              EntityMatcher entityMatcher,
                              SourceCitationFormatter sourceCitationFormatter) {
```

and inside the constructor body, after `this.entityMatcher = entityMatcher;`:

```java
        this.sourceCitationFormatter = sourceCitationFormatter;
```

`SourceCitationFormatter` is in the same package (`...service`), so no import is needed.

- [ ] **Step 2: Add the citation instruction to the system prompt**

In the `systemPrompt` text block (starts around line 162), update the `### 📚 From Official Documentation` line to instruct citing. Replace:

```java
        ### 📚 From Official Documentation
        [Provide information found ONLY using the search tool here. If the tool returned no results, state "No specific documentation found for this query."]
```

with:

```java
        ### 📚 From Official Documentation
        [Provide information found ONLY using the search tool here. If the tool returned no results, state "No specific documentation found for this query."]
        When you use information from the search tool, reference the source document's filename in your answer. Each tool result chunk is prefixed with its source as [Source: <filename>]; mention that exact filename (e.g. WorkingWithDynamo.md) when you rely on its content.
```

- [ ] **Step 3: Build and stream the footer in onComplete**

In the `.onComplete(response -> { ... })` block, locate the cache-write section:

```java
                            log.info("Updating semantic cache with new answer [traceId={}]", traceId);
                            Metadata metadata = new Metadata();
```

Insert the footer logic immediately **before** that `log.info("Updating semantic cache...")` line:

```java
                            // Append the Sources footer (cited vs. retrieved) before caching,
                            // so cached answers retain it. Stream it to the live client too.
                            String sourcesFooter = sourceCitationFormatter.format(
                                    auditContext.getRetrievedSources(), fullResponse.toString());
                            if (!sourcesFooter.isEmpty()) {
                                handler.onNext(sourcesFooter);
                                fullResponse.append(sourcesFooter);
                            }

```

The existing `cacheStore.add(queryEmbedding, TextSegment.from(fullResponse.toString(), metadata));` then stores the footer-inclusive text, and the final `handler.onComplete(...)` carries `fullResponse.toString()` (also footer-inclusive).

- [ ] **Step 4: Compile to verify it builds**

Run: `./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: PASS — all tests green, including the existing suite (no regressions from the constructor change).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/ai/agent/ddbknowledge/service/QueryRoutingService.java
git commit -m "feat: stream and cache Sources footer in QueryRoutingService"
```

---

## Task 6: Document the feature in README

**Files:**
- Modify: `README.md`

Add a short note (in the Database Management / Testing area) that answers now include a Sources footer linking to `/sources/<file>`, and that raw docs are served from the ingest docs-path.

- [ ] **Step 1: Locate the section**

Run: `grep -n "Database Management\|Testing\|/cache\|/docs" README.md`
Expected: line numbers for the relevant section(s). Pick the Database Management / endpoints area.

- [ ] **Step 2: Add the documentation**

Insert this subsection in that area (adapt heading depth to match surrounding headings):

```markdown
### Source Citations

Answers that draw on the ingested documentation end with a **Sources** footer listing every
document retrieved for the request:

- ✅ `cited` — the model referenced the filename in its answer.
- 📄 `retrieved` — pulled by search but not referenced.

Each entry links to `/sources/<file_name>`, which serves the raw markdown of that doc from the
ingest docs-path (`agent.ingest.docs-path`, default `local_test_docs`) as `text/plain`. The
footer is generated on a cache miss and stored with the answer, so cached responses keep it.
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document Sources footer and /sources endpoint"
```

---

## Final Verification

- [ ] **Run the full suite once more**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Optional manual smoke test** (requires DB + model env per README)

1. Start the app: `./gradlew bootRun`
2. Ask a documentation-backed question via the UI or `curl 'http://localhost:8080/ask-stream?question=How+do+I+create+a+table'`.
3. Confirm the answer ends with a `**Sources:**` footer.
4. Click (or `curl`) a `/sources/<file>.md` link and confirm the raw markdown is returned.
5. Confirm `/sources/../something` returns `400` and `/sources/Missing.md` returns `404`.

---

## Self-Review Notes (for the executor)

- **Spec coverage:** Goals → footer (Task 2/5), cited vs. retrieved (Task 2), clickable `/sources/` links (Task 4), cached footer (Task 5). Non-goals respected (no line anchors, no re-ingestion). All five edge-case rows map to: empty set (Task 2), no matches (Task 3 unchanged branch), dedupe (Task 1 `LinkedHashSet`), missing `file_name` (Task 3), unsafe filename (Task 4).
- **Type consistency:** `addRetrievedSource(String)`, `getRetrievedSources()` (Lombok), `format(Set<String>, String)`, `getSource(String)` are used identically across tasks.
- **Order:** Tasks 1→2→4 are independent; Task 3 depends on Task 1; Task 5 depends on Tasks 1, 2, 3. Execute in numeric order.
