package io.cubercise.fsbrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * HTTP seam for DELETE /api/file in read-write mode (FSB_MODE set). Every
 * test seeds fresh fixtures. Failure modes: missing entry 404, non-empty
 * directory 409 (refused — no recursive walk in v1), pathy/blank/null-byte
 * names 400, missing parent dir 404, traversal parent 400. Success is 204
 * with an empty body, and empty directories do delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeleteReadWriteTest {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("FSB_ROOT", () -> root.toAbsolutePath().toString());
        registry.add("FSB_MODE", () -> "read-write");
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void seed() throws Exception {
        Files.createDirectories(root.resolve("sub"));
    }

    @Test
    void deletesAFileWithNoContent() throws Exception {
        Path file = root.resolve("sub/doomed.txt");
        Files.writeString(file, "bye");

        mockMvc.perform(deleteEntry("sub", "doomed.txt"))
                .andExpect(status().isNoContent());

        assertThat(file).doesNotExist();
    }

    @Test
    void deletesAFileInTheRootItself() throws Exception {
        Path file = root.resolve("root-doomed.txt");
        Files.writeString(file, "top bye");

        mockMvc.perform(deleteEntry("", "root-doomed.txt"))
                .andExpect(status().isNoContent());

        assertThat(file).doesNotExist();
    }

    @Test
    void deletingAMissingEntryIsNotFound() throws Exception {
        mockMvc.perform(deleteEntry("sub", "ghost.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingANonEmptyDirectoryIsConflict() throws Exception {
        Files.createDirectories(root.resolve("full"));
        Files.writeString(root.resolve("full/keep.txt"), "kept");

        mockMvc.perform(deleteEntry("", "full"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").isNotEmpty());

        // Directory and its content survived.
        assertThat(root.resolve("full/keep.txt")).hasContent("kept");
        assertThat(root.resolve("full")).isDirectory();
    }

    @Test
    void deletingAnEmptyDirectorySucceedsWithNoContent() throws Exception {
        Files.createDirectories(root.resolve("hollow"));

        mockMvc.perform(deleteEntry("", "hollow"))
                .andExpect(status().isNoContent());

        assertThat(root.resolve("hollow")).doesNotExist();
    }

    @Test
    void pathyNamesAreRejected() throws Exception {
        Files.writeString(root.resolve("sub/keep.txt"), "keep");
        for (String bad : new String[] {"a/b", "../x", "..\\evil", "/abs", "a\\b", ".", "..", "evil\0.txt", "  "}) {
            mockMvc.perform(deleteEntry("sub", bad))
                    .andExpect(status().isBadRequest());
        }
        try (var stream = Files.list(root.resolve("sub"))) {
            assertThat(stream.map(p -> p.getFileName().toString())).containsExactly("keep.txt");
        }
    }

    @Test
    void deleteInAMissingDirectoryIsNotFound() throws Exception {
        mockMvc.perform(deleteEntry("missing-dir", "any.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void traversalParentIsRejected() throws Exception {
        mockMvc.perform(deleteEntry("../..", "any.txt"))
                .andExpect(status().isBadRequest());
    }

    /** Builds the DELETE /api/file?path=…&name=… request with the exact contract. */
    private static MockHttpServletRequestBuilder deleteEntry(String path, String name) {
        var builder = delete("/api/file");
        if (path != null && !path.isEmpty()) {
            builder.param("path", path);
        }
        if (name != null) {
            builder.param("name", name);
        }
        return builder;
    }
}
