package io.cubercise.fsbrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HTTP seam for POST /api/rename in read-write mode (FSB_MODE set). Every
 * test seeds its own fixtures with fresh names, so the class has no
 * test-ordering dependencies. Each failure mode has exactly one status:
 * file and directory renames 200 (children move with the directory),
 * duplicate target 409 (never an overwrite), missing source 404,
 * pathy/blank/dot/null-byte names 400, missing parent dir 404,
 * traversal parent 400, malformed or field-less bodies 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RenameReadWriteTest {

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
    void renamesAFileWithinItsDirectory() throws Exception {
        Files.writeString(root.resolve("sub/alpha.txt"), "content-A");

        mockMvc.perform(renameJson("sub", "alpha.txt", "beta.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("beta.txt"));

        assertThat(root.resolve("sub/alpha.txt")).doesNotExist();
        assertThat(root.resolve("sub/beta.txt")).hasContent("content-A");
    }

    @Test
    void renamesAFileInTheRootItself() throws Exception {
        Files.writeString(root.resolve("root-level.txt"), "top");

        mockMvc.perform(renameJson("", "root-level.txt", "root-level-renamed.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("root-level-renamed.txt"));

        assertThat(root.resolve("root-level.txt")).doesNotExist();
        assertThat(root.resolve("root-level-renamed.txt")).hasContent("top");
    }

    @Test
    void renamesADirectoryAndItsChildrenMoveWithIt() throws Exception {
        Files.createDirectories(root.resolve("pkg/nested"));
        Files.writeString(root.resolve("pkg/inner.txt"), "inner");
        Files.writeString(root.resolve("pkg/nested/deep.txt"), "deep");

        mockMvc.perform(renameJson("", "pkg", "box"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("box"));

        assertThat(root.resolve("pkg")).doesNotExist();
        assertThat(root.resolve("box/inner.txt")).hasContent("inner");
        assertThat(root.resolve("box/nested/deep.txt")).hasContent("deep");
    }

    @Test
    void renameOntoAnExistingNameIsConflict() throws Exception {
        Files.writeString(root.resolve("sub/one.txt"), "one");
        Files.writeString(root.resolve("sub/two.txt"), "two");

        mockMvc.perform(renameJson("sub", "one.txt", "two.txt"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());

        // Neither side was touched.
        assertThat(root.resolve("sub/one.txt")).hasContent("one");
        assertThat(root.resolve("sub/two.txt")).hasContent("two");
    }

    @Test
    void renameOntoItselfIsConflict() throws Exception {
        // A no-op rename still names an existing target: 409, never a
        // destructive pass-through.
        Files.writeString(root.resolve("sub/self.txt"), "self");

        mockMvc.perform(renameJson("sub", "self.txt", "self.txt"))
                .andExpect(status().isConflict());

        assertThat(root.resolve("sub/self.txt")).hasContent("self");
    }

    @Test
    void renamingAMissingEntryIsNotFound() throws Exception {
        mockMvc.perform(renameJson("sub", "ghost.txt", "whatever.txt"))
                .andExpect(status().isNotFound());
        assertThat(root.resolve("sub/whatever.txt")).doesNotExist();
    }

    @Test
    void pathyToNamesAreRejected() throws Exception {
        Path dir = Files.createDirectories(root.resolve("pathy-to"));
        Files.writeString(dir.resolve("keep.txt"), "keep");
        for (String bad : new String[] {"a/b", "../x", "..\\evil", "/abs", "a\\b", ".", "..", "evil\0.txt", "   "}) {
            mockMvc.perform(renameJson("pathy-to", "keep.txt", bad))
                    .andExpect(status().isBadRequest());
        }
        // Nothing was created and nothing moved — not even a normalized basename.
        try (var stream = Files.list(dir)) {
            assertThat(stream.map(p -> p.getFileName().toString())).containsExactly("keep.txt");
        }
    }

    @Test
    void pathyFromNamesAreRejected() throws Exception {
        Path dir = Files.createDirectories(root.resolve("pathy-from"));
        for (String bad : new String[] {"../x", "a/b", "evil\0.txt"}) {
            mockMvc.perform(renameJson("pathy-from", bad, "fresh.txt"))
                    .andExpect(status().isBadRequest());
        }
        try (var stream = Files.list(dir)) {
            assertThat(stream.toList()).isEmpty();
        }
    }

    @Test
    void missingOrBlankBodyFieldsAreRejected() throws Exception {
        // Missing 'from'/'to' (null after JSON binding) → 400 before the
        // filesystem is ever touched.
        mockMvc.perform(jsonBody("{\"path\":\"sub\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody("{\"path\":\"sub\",\"from\":\"x.txt\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(jsonBody("{}")).andExpect(status().isBadRequest());
        // Malformed JSON → 400 by the message-conversion layer.
        mockMvc.perform(jsonBody("not-json")).andExpect(status().isBadRequest());
        try (var stream = Files.list(root.resolve("sub"))) {
            assertThat(stream.toList()).isEmpty();
        }
    }

    @Test
    void renameInAMissingDirectoryIsNotFound() throws Exception {
        mockMvc.perform(renameJson("missing-dir", "any.txt", "other.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void traversalParentIsRejected() throws Exception {
        mockMvc.perform(renameJson("../..", "any.txt", "other.txt"))
                .andExpect(status().isBadRequest());
    }

    /** Builds the POST /api/rename request with the exact body contract. */
    private static MockHttpServletRequestBuilder renameJson(String path, String from, String to) {
        return jsonBody("{\"path\":" + jsonString(path) + ",\"from\":" + jsonString(from) + ",\"to\":" + jsonString(to) + "}");
    }

    private static MockHttpServletRequestBuilder jsonBody(String json) {
        return post("/api/rename").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** JSON string literal with the escapes the pathy-name cases need. */
    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\0", "\\u0000") + "\"";
    }
}
