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
