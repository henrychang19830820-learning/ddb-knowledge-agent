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
