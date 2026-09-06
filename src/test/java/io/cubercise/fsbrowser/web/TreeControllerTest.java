package io.cubercise.fsbrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP seam for browsing: GET /api/tree must list a directory's entries
 * (directories first, then case-insensitive alphabetical files) and the
 * Sandbox must reject every escape attempt before any listing I/O.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TreeControllerTest {

    @TempDir
    static Path root;

    /** A second temp dir that is deliberately outside the Root. */
    @TempDir
    static Path outside;

    @DynamicPropertySource
    static void rootProperty(DynamicPropertyRegistry registry) {
        registry.add("FSB_ROOT", () -> root.toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void seed() throws Exception {
        // Listing shape/order fixture: case-mixed names prove dirs-first and
        // case-insensitive collation (ASCII order would sort "Beta" < "alpha").
        Files.createDirectories(root.resolve("shape"));
        Files.createDirectory(root.resolve("shape/Beta"));
        Files.createDirectory(root.resolve("shape/alpha"));
        Files.writeString(root.resolve("shape/apple.txt"), "12345678");
        Files.writeString(root.resolve("shape/BANANA.txt"), "banana");
        Files.writeString(root.resolve("shape/Cherry.md"), "cherry!");

        // Nested navigation fixture.
        Files.createDirectories(root.resolve("sub/dir"));
        Files.writeString(root.resolve("sub/dir/file.txt"), "nested");

        // Empty directory fixture.
        Files.createDirectory(root.resolve("empty"));

        // Symlink fixtures: one escaping the Root, one staying inside it.
        Files.writeString(outside.resolve("secret.txt"), "outside");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        Files.createSymbolicLink(root.resolve("inside"), root.resolve("sub"));
    }

    @Test
    void listsTheRootWhenPathIsOmitted() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(root.toAbsolutePath().toString()))
                .andExpect(jsonPath("$.entries[?(@.name == 'sub')].kind").value("DIR"))
                .andExpect(jsonPath("$.entries[?(@.name == 'escape')].kind").value("DIR"))
                .andReturn();
        // Global invariant: every DIR entry precedes every FILE entry.
        assertThat(dirsBeforeFiles(entryKinds(result))).isTrue();
    }

    @Test
    void listsDirectoriesFirstThenAlphabeticalCaseInsensitive() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "shape"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(root.resolve("shape").toAbsolutePath().toString()))
                .andExpect(jsonPath("$.entries.length()").value(5))
                .andExpect(jsonPath("$.entries[0].name").value("alpha"))
                .andExpect(jsonPath("$.entries[0].kind").value("DIR"))
                .andExpect(jsonPath("$.entries[1].name").value("Beta"))
                .andExpect(jsonPath("$.entries[1].kind").value("DIR"))
                .andExpect(jsonPath("$.entries[2].name").value("apple.txt"))
                .andExpect(jsonPath("$.entries[2].kind").value("FILE"))
                .andExpect(jsonPath("$.entries[3].name").value("BANANA.txt"))
                .andExpect(jsonPath("$.entries[4].name").value("Cherry.md"))
                .andExpect(jsonPath("$.entries[2].size").value(8))
                .andExpect(jsonPath("$.entries[3].size").value(6))
                .andExpect(jsonPath("$.entries[2].lastModified").isNumber());
    }

    @Test
    void navigatesNestedDirectories() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "sub/dir"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(root.resolve("sub/dir").toAbsolutePath().toString()))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].name").value("file.txt"))
                .andExpect(jsonPath("$.entries[0].kind").value("FILE"))
                .andExpect(jsonPath("$.entries[0].size").value(6));
    }

    @Test
    void listsEmptyDirectoryAsEmptyEntries() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void followsSymlinksThatStayInsideTheRoot() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "inside"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].name").value("dir"));
    }

    @Test
    void rejectsDotDotTraversal() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "sub/../.."))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tree").queryParam("path", ".."))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUrlEncodedDotDotTraversal() throws Exception {
        // Literal %2e%2e%2f%2e%2e in the query: a URI object bypasses
        // MockMvc's URI-template encoding so the server sees the raw escape.
        mockMvc.perform(get(URI.create("/api/tree?path=%2e%2e%2f%2e%2e")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAbsolutePathsOutsideTheRoot() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", outside.toAbsolutePath().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSymlinkEscapingTheRoot() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "escape"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNullBytesInPath() throws Exception {
        mockMvc.perform(get(URI.create("/api/tree?path=a%00b")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingDirectoryIsNotFound() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pathToAFileIsNotFound() throws Exception {
        mockMvc.perform(get("/api/tree").queryParam("path", "shape/apple.txt"))
                .andExpect(status().isNotFound());
    }

    private static List<String> entryKinds(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        List<String> kinds = com.jayway.jsonpath.JsonPath.read(json, "$.entries[*].kind");
        return kinds;
    }

    private static boolean dirsBeforeFiles(List<String> kinds) {
        int firstFile = kinds.indexOf("FILE");
        if (firstFile < 0) {
            return true;
        }
        return !kinds.subList(firstFile, kinds.size()).contains("DIR");
    }
}
