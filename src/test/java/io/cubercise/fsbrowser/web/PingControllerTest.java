package io.cubercise.fsbrowser.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP seam: the ping endpoint must prove the backend is live and report the
 * configured Root (FSB_ROOT pointed at a temp directory for the test).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PingControllerTest {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void rootProperty(DynamicPropertyRegistry registry) {
        registry.add("FSB_ROOT", () -> root.toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void pingReportsLiveBackendAndConfiguredRoot() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pong").value(true))
                .andExpect(jsonPath("$.root").value(root.toAbsolutePath().toString()));
    }
}
