package io.cubercise.fsbrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP seam for the write gate covering rename and delete in the default
 * (read-only) deployment: FSB_MODE unset means both write endpoints answer
 * 403 with an {"error": …} body and — provably — nothing on disk changes,
 * no matter how valid the rest of the request is. The guard-first ordering
 * is pinned here too: traversal-shaped input in read-only mode still
 * answers 403 (the mode refusal wins), exactly like upload's.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MutationsReadOnlyTest {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // FSB_MODE deliberately NOT set: the default under test.
        registry.add("FSB_ROOT", () -> root.toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void seed() throws Exception {
        Files.writeString(root.resolve("untouchable.txt"), "do not touch");
    }

    @BeforeEach
    void cleanSlate() {
        // Prove "nothing changed" per test, not just once.
        assertDiskUntouched();
    }

    @Test
    void reportsReadOnlyMode() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("read-only"));
    }

    @Test
    void renameIsForbiddenInTheDefaultMode() throws Exception {
        mockMvc.perform(post("/api/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"\",\"from\":\"untouchable.txt\",\"to\":\"renamed.txt\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        assertDiskUntouched();
    }

    @Test
    void deleteIsForbiddenInTheDefaultMode() throws Exception {
        mockMvc.perform(delete("/api/file?path=&name=untouchable.txt"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        assertDiskUntouched();
    }

    @Test
    void theModeRefusalWinsOverPathAndBodyErrors() throws Exception {
        // A traversal-shaped parent would be 400 and a missing dir 404 in
        // read-write mode; read-only must answer 403 regardless — the guard
        // runs before path resolution and body parsing.
        mockMvc.perform(post("/api/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"../..\",\"from\":\"x\",\"to\":\"y\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/file?path=..%2F..&name=x"))
                .andExpect(status().isForbidden());
        // Missing body entirely — still 403, not 400.
        mockMvc.perform(post("/api/rename"))
                .andExpect(status().isForbidden());
        assertDiskUntouched();
    }

    /** The Root must still hold exactly the seeded file, unchanged. */
    private static void assertDiskUntouched() {
        try (var stream = Files.list(root)) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                    .containsExactly("untouchable.txt");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
