package io.cubercise.fsbrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * HTTP seam for the upload write path in read-write mode (FSB_MODE set):
 * a valid multipart upload lands as a new Entry with byte-identical
 * content (hash-compared), and every rule failure has exactly one status —
 * duplicate name 409, pathy/empty/null-byte filenames 400, missing target
 * dir 404, traversal target 400, non-multipart or part-less requests 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadReadWriteTest {

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
        Files.writeString(root.resolve("sub/existing.txt"), "already there");
        Files.writeString(root.resolve("occupied.txt"), "do not clobber");
    }

    @Test
    void reportsReadWriteMode() throws Exception {
        mockMvc.perform(get("/api/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("read-write"));
    }

    @Test
    void storesAnUploadedFileAsANewEntryWithIdenticalBytes() throws Exception {
        // Enough bytes to prove real streaming, with non-ASCII content so a
        // charset bug would flip the hash.
        byte[] payload = ("fs-browser upload test — line %d\n".formatted(7))
                .repeat(64)
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipartUpload("sub", "uploaded.bin", payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("uploaded.bin"))
                .andExpect(jsonPath("$.size").value(payload.length));

        Path stored = root.resolve("sub/uploaded.bin");
        assertThat(Files.exists(stored)).isTrue();
        assertThat(Files.size(stored)).isEqualTo(payload.length);
        // The proof that matters: byte-for-byte identical content.
        assertThat(org.springframework.util.DigestUtils.md5DigestAsHex(payload))
                .isEqualTo(org.springframework.util.DigestUtils.md5DigestAsHex(Files.readAllBytes(stored)));
        // And sha-256 as well, since the boot proof uses it.
        assertThat(digest(payload)).isEqualTo(digest(Files.readAllBytes(stored)));
    }

    @Test
    void uploadsIntoTheRootItself() throws Exception {
        byte[] payload = "root-level".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipartUpload("", "root-level.txt", payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("root-level.txt"))
                .andExpect(jsonPath("$.size").value(payload.length));
        assertThat(root.resolve("root-level.txt")).hasContent("root-level");
    }

    @Test
    void duplicateNameIsConflict() throws Exception {
        mockMvc.perform(multipartUpload("sub", "existing.txt", "clobber".getBytes()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());
        // The original content survived.
        assertThat(root.resolve("sub/existing.txt")).hasContent("already there");
    }

    @Test
    void duplicateRootLevelNameIsConflict() throws Exception {
        mockMvc.perform(multipartUpload("", "occupied.txt", "clobber".getBytes()))
                .andExpect(status().isConflict());
        assertThat(root.resolve("occupied.txt")).hasContent("do not clobber");
    }

    @Test
    void pathyFilenamesAreRejected() throws Exception {
        for (String bad : new String[] {"../x", "a/b", "..\\evil", "/abs", "a\\b"}) {
            mockMvc.perform(multipartUpload("sub", bad, "x".getBytes()))
                    .andExpect(status().isBadRequest());
        }
        // Nothing was created anywhere — not even a normalized basename.
        try (var stream = Files.list(root.resolve("sub"))) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                    .doesNotContain("x", "b", "evil", "abs")
                    .containsExactlyInAnyOrder("existing.txt", "uploaded.bin");
        }
    }

    @Test
    void nullByteFilenameIsRejected() throws Exception {
        mockMvc.perform(multipartUpload("sub", "evil\0.txt", "x".getBytes()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dotAndDotDotNamesAreRejected() throws Exception {
        mockMvc.perform(multipartUpload("sub", ".", "x".getBytes()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipartUpload("sub", "..", "x".getBytes()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyOrMissingFilenameIsRejected() throws Exception {
        // Empty after stripping.
        mockMvc.perform(multipartUpload("sub", "  ", "x".getBytes()))
                .andExpect(status().isBadRequest());
        // A 'file' part with no filename at all (a plain form field), not
        // a missing part — still nothing storable.
        MockMultipartFile noName = new MockMultipartFile("file", null, null, "payload".getBytes());
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/upload?path=sub").file(noName))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTargetDirectoryIsNotFound() throws Exception {
        mockMvc.perform(multipartUpload("missing-dir", "any.txt", "x".getBytes()))
                .andExpect(status().isNotFound());
    }

    @Test
    void traversalTargetDirectoryIsRejected() throws Exception {
        mockMvc.perform(multipartUpload("../..", "any.txt", "x".getBytes()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMultipartRequestsAreRejected() throws Exception {
        mockMvc.perform(post("/api/upload").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/upload?path=sub"))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder multipartUpload(String dir, String filename, byte[] content) {
        MockMultipartFile part = new MockMultipartFile("file", filename, "application/octet-stream", content);
        var builder = MockMvcRequestBuilders.multipart("/api/upload");
        if (!dir.isEmpty()) {
            builder.param("path", dir);
        }
        return builder.file(part);
    }

    private static String digest(byte[] bytes) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
