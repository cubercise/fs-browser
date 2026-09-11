package io.cubercise.fsbrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP seam for the write gate in the default (read-only) deployment:
 * FSB_MODE unset means every write endpoint answers 403 with an
 * {"error": …} body and — provably — nothing is ever written into the
 * Root, no matter how valid the rest of the request is.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadReadOnlyTest {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // FSB_MODE deliberately NOT set: the default under test.
        registry.add("FSB_ROOT", () -> root.toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void cleanSlate() {
        // Prove "no file written" per test, not just once.
        assertNoUploads();
    }

    @Test
    void reportsReadOnlyMode() throws Exception {
        mockMvc.perform(get("/api/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("read-only"));
    }

    @Test
    void uploadIsForbiddenInTheDefaultMode() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/upload")
                        .file(new MockMultipartFile("file", "hello.txt", "text/plain", "hi".getBytes())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        assertNoUploads();
    }

    @Test
    void theModeRefusalWinsOverPathAndBodyErrors() throws Exception {
        // A traversal-shaped target would be 400 and a missing dir 404 in
        // read-write mode; read-only must answer 403 regardless — the guard
        // runs before path resolution.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/upload")
                        .file(new MockMultipartFile("file", "hello.txt", "text/plain", "hi".getBytes()))
                        .param("path", "../.."))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/upload?path=missing-dir"))
                .andExpect(status().isForbidden());
        assertNoUploads();
    }

    /** The Root must contain nothing but the (empty) temp dir itself. */
    private static void assertNoUploads() {
        try (var stream = Files.list(root)) {
            assertThat(stream.toList()).isEmpty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
