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
