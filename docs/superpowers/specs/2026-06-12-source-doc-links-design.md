# Source-Doc Links in Answers — Design

**Date:** 2026-06-12
**Status:** Approved (pending spec review)

## Problem

When the agent answers using information retrieved from ingested documentation, the
response gives no indication of *which* source documents the knowledge came from. Users
cannot verify a claim against the original doc. We want each answer to cite the source
documents of the chunks that were retrieved, with clickable links to those docs.

## Goals

- After an answer that used the documentation tool, append a **Sources** footer listing
  **every** document retrieved during the request.
- Mark each listed source as **cited** (the model referenced its filename in the answer)
  or **retrieved** (pulled by search but not referenced by the model).
- Each source is a clickable link to the locally-served raw markdown file.
- Cached answers retain their Sources footer.

## Non-Goals (v1)

- Line-number or anchor-level linking (we only have chunk `index`, not line offsets — see
  "Data available" below).
- Rendered-HTML doc viewer or browser text-fragment ("jump to passage") linking.
- Linking to external AWS documentation URLs.
- Re-ingestion or any change to how chunks are stored.

## Data available

Each chunk's metadata (verified against the live DB) contains:

```json
{
  "index": "0",
  "file_name": "WorkingWithDynamo.md",
  "timestamp": "2026-06-12T07:10:30.620308Z",
  "chunking_strategy": "recursive-1000-200",
  "absolute_directory_path": "/Users/sansword/Source/github/ddb-knowledge-agent/local_test_docs"
}
```

- `file_name` — the source document. **This is what we link to.**
- `index` — the chunk's ordinal position within the document, **not** a line number.
- The recursive splitter discards line/character offsets, so file-level is the finest
  granularity available without re-ingestion.

Both retrieval paths preserve this metadata:
- Vector search (`knowledgeStore`, pgvector) deserializes the JSONB `metadata` column back
  into `TextSegment` metadata.
- Keyword search (`KeywordSearchRepository`) explicitly parses the `metadata` JSON into the
  `TextSegment`.
- `HybridSearchService` carries the original `embedded()` segment through RRF fusion, so
  `file_name` survives to the tool.

`DocumentationTool` currently calls `match.embedded().text()` and **discards** the metadata.
That is the single point where source information is lost today.

## Output format

When ≥1 chunk was retrieved, append the following to the answer (after the existing
`📚` / `🧠` sections):

```markdown
---
**Sources:**
- ✅ [WorkingWithDynamo.md](/sources/WorkingWithDynamo.md) — cited
- 📄 [GettingStarted.md](/sources/GettingStarted.md) — retrieved
```

Rules:
- **Root-relative** links (`/sources/<file_name>`) — resolve correctly in the browser
  regardless of host/port, and read fine as raw text for curl users.
- All retrieved sources are listed, de-duplicated, in first-retrieved order.
- A source is **✅ cited** if its exact `file_name` string appears anywhere in the model's
  response text; otherwise **📄 retrieved**.
- If no chunks were retrieved (model did not call the tool, or search returned nothing),
  **no footer is added**.

The UI (`static/index.html`) renders the accumulated response with `marked.parse()`, so the
markdown links display as clickable anchors with no frontend change. curl users see raw
markdown.

## Architecture & components

### 1. `AuditContext` (audit/AuditContext.java)
Add an ordered, de-duplicating set to carry retrieved sources through the request:
- New field: `Set<String> retrievedSources` initialized to a `LinkedHashSet` (insertion
  order = first-retrieved order, automatic dedupe).
- New method: `addRetrievedSource(String fileName)` — null/blank-safe; ignores empties.

`AuditContext` is already created per request in `QueryRoutingService.askStreaming` and held
in the `ThreadLocal` `AuditContextHolder`, so it is the natural carrier — the tool writes to
it, the completion handler reads from it.

### 2. `DocumentationTool` (tool/DocumentationTool.java)
For each `EmbeddingMatch` returned by `hybridSearchService.search(...)`:
- Read `match.embedded().metadata().getString("file_name")`.
- Record it via `context.addRetrievedSource(fileName)` (alongside the existing
  `addToolExecution` call).
- Tag the chunk text handed back to the model so it can cite, e.g. prefix each chunk with
  `[Source: <file_name>]\n` before joining. When `file_name` is absent, omit the tag.

The "no results" branch is unchanged (records nothing).

### 3. `SourceCitationFormatter` (new — `service/SourceCitationFormatter.java`)
A pure, dependency-free, unit-testable formatter (Spring `@Component` so it can be injected,
but holds no state and calls nothing external).

- Input: `Set<String> retrievedSources`, `String responseText`.
- Output: `String` — the markdown footer, or `""` when `retrievedSources` is empty.
- Logic:
  - For each source, `cited = responseText != null && responseText.contains(fileName)`.
  - Render `- ✅ [<file>](/sources/<file>) — cited` or `- 📄 [<file>](/sources/<file>) — retrieved`.
  - Prepend the `\n\n---\n**Sources:**\n` header.
- No streaming, no Spring, no model — just string assembly. Keeps the labeling logic out of
  the already-large `QueryRoutingService`.

### 4. `QueryRoutingService` (service/QueryRoutingService.java)
- **System prompt:** add an instruction that, for any information taken from the search tool,
  the model should reference the source filename (which appears as `[Source: <file>]` in the
  tool output) in its answer.
- **`onComplete`:** after the model's stream completes and `fullResponse` is assembled:
  1. `String footer = sourceCitationFormatter.format(auditContext.getRetrievedSources(), fullResponse.toString());`
  2. If `footer` is non-empty: `handler.onNext(footer)` to stream it to the client, then
     `fullResponse.append(footer)`.
  3. Proceed with the existing audit write and `cacheStore.add(...)` using the
     footer-inclusive `fullResponse`, so cached answers carry the footer.
- Inject `SourceCitationFormatter` via the constructor.

### 5. `GET /sources/{filename}` (new endpoint)
Serves the raw markdown of an ingested doc.

- Location: a new `SourceController` (keeps `QueryController` focused; `QueryController`
  already owns `/ingest`, `/docs`, `/cache`, `/ask-stream`).
- Reads the docs directory from the same property `QueryController` uses:
  `${agent.ingest.docs-path}` (default `local_test_docs`).
- **Path-traversal protection:** accept a bare filename only. Reject any value containing
  `/`, `\`, or `..`, or that is not a simple `*.md` name. Resolve against the docs dir and
  verify the normalized path still has the docs dir as parent before reading.
- Response: `200` with body = file contents, `Content-Type: text/plain;charset=UTF-8`.
  Rationale: `text/plain` displays inline in the browser, whereas `text/markdown` makes some
  browsers download the file. v1 goal is viewing the raw source, so inline display wins.
- Missing/!readable file → `404`. Rejected (unsafe) name → `400`.

## Data flow (cache-miss path)

```
askStreaming
  → model ReAct loop
      → DocumentationTool.searchDocumentation(query)
          → HybridSearchService.search → matches (with file_name metadata)
          → for each match: AuditContext.addRetrievedSource(file_name)
          → return chunk text tagged with [Source: file_name]
      → model streams answer, may reference filenames
  → onComplete:
      footer = SourceCitationFormatter.format(retrievedSources, fullResponse)
      if footer not empty: stream footer + append to fullResponse
      audit write + cacheStore.add(fullResponse incl. footer)

User clicks /sources/WorkingWithDynamo.md → SourceController serves raw .md
```

## Cache interaction

- On a **cache miss**, the footer is appended to `fullResponse` before `cacheStore.add`, so
  the stored answer already includes the Sources footer.
- On a **cache hit**, the cached text (footer included) is returned verbatim. The cited /
  retrieved labels reflect the *original* generation, which is acceptable because the answer
  itself is being reused. No recomputation on hits.

## Edge cases

| Case | Behavior |
|------|----------|
| Model never calls the tool | `retrievedSources` empty → no footer. |
| Search returns no matches | Tool records nothing → no footer. |
| Same doc in multiple chunks | De-duplicated by the `LinkedHashSet`. |
| Chunk missing `file_name` | Skipped (not added, not tagged). |
| Model cites a filename it wasn't given | Irrelevant — labels are computed only over the retrieved set. |
| Unsafe `/sources/` filename | `400`; never reads outside the docs dir. |

## Testing

- **`SourceCitationFormatterTest`** (new): cited vs retrieved labeling; empty set → empty
  string; de-duplication; a source whose filename appears in the response is marked cited.
- **`DocumentationToolTest`** (extend): retrieved `file_name`s are recorded into
  `AuditContext`; chunk text is tagged with `[Source: ...]`; missing metadata is skipped.
- **`SourceController` test** (new): valid `*.md` served with correct content-type; `..`/
  slash/`\` rejected with `400`; missing file → `404`.

## Documentation

Update `README.md` (Database Management / Testing area) to note that answers now include a
Sources footer linking to `/sources/<file>`, and that the raw docs are served from the
ingest docs-path.
