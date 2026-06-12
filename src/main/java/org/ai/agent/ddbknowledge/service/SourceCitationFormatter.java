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
