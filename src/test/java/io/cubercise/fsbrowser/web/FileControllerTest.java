package io.cubercise.fsbrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP seam for the file endpoint: GET /api/file must serve one file of the
 * Root, classified by content (never extension) — text inline with detected
 * charset and a safe 512 KB cap, images inline, everything else as an
 * attachment — and the Sandbox must reject escapes exactly as it does for
 * /api/tree.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileControllerTest {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void rootProperty(DynamicPropertyRegistry registry) {
        registry.add("FSB_ROOT", () -> root.toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void seed() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "héllo wörld\n");

        // ~512 KB of a single 3-byte Thai character: the cap cut at byte
        // 524288 is guaranteed to land mid-character (524288 % 3 == 2), and
        // the file is larger than the cap.
        byte[] thaiChar = "ฬ".getBytes(StandardCharsets.UTF_8);
        int repeats = (FileController.TEXT_CAP_BYTES + thaiChar.length) / thaiChar.length;
        byte[] big = new byte[repeats * thaiChar.length];
        for (int i = 0; i < big.length; i += thaiChar.length) {
            big[i] = thaiChar[0];
            big[i + 1] = thaiChar[1];
            big[i + 2] = thaiChar[2];
        }
        Files.write(root.resolve("big-thai.txt"), big);

        // ISO-8859-1: 'é' as a single 0xE9 byte is invalid UTF-8 but printable Latin-1.
        Files.write(root.resolve("latin1.txt"), new byte[]{'c', 'a', 'f', (byte) 0xE9, '\n'});

        // UTF-16 LE with BOM.
        Files.write(root.resolve("utf16.txt"),
                "\uFEFFsuomi på svenska".getBytes("UTF-16LE"));

        // Real (minimal) PNG: 8-byte magic + IHDR-ish tail; only magic matters here.
        byte[] png = new byte[64];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        png[4] = 0x0D;
        png[5] = 0x0A;
        png[6] = 0x1A;
        png[7] = 0x0A;
        png[8] = 0x00;
        png[9] = 0x00;
        png[10] = 0x00;
        png[11] = 0x0D;
        Files.write(root.resolve("photo.png"), png);

        Files.writeString(root.resolve("icon.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");

        // Random-ish binary: NULs make it neither UTF-8 nor printable Latin-1.
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        Files.write(root.resolve("data.bin"), binary);

        // Non-ASCII filename for the RFC 5987 encoding check.
        Files.write(root.resolve("日本語データ.bin"), binary);

        Files.createDirectories(root.resolve("somedir"));
    }

    @Test
    void servesTextInlineWithDetectedCharset() throws Exception {
        mockMvc.perform(get("/api/file").queryParam("path", "hello.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(header().string("Content-Length", String.valueOf("héllo wörld\n".getBytes(StandardCharsets.UTF_8).length)))
                .andExpect(header().doesNotExist("X-Fsb-Truncated"))
                .andExpect(content().bytes("héllo wörld\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void detectsIso8859TextByContentNotExtension() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/file").queryParam("path", "latin1.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("ISO-8859-1")))
                .andReturn();
        assertThat(result.getResponse().getHeader("Content-Type")).contains("charset=ISO-8859-1");
        // Decoded with the served charset, the byte 0xE9 is 'é'.
        assertThat(new String(result.getResponse().getContentAsByteArray(),
                result.getResponse().getCharacterEncoding())).isEqualTo("café\n");
    }

    @Test
    void detectsUtf16ByBom() throws Exception {
        mockMvc.perform(get("/api/file").queryParam("path", "utf16.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("UTF-16")))
                .andExpect(content().bytes("\uFEFFsuomi på svenska".getBytes("UTF-16LE")));
    }

    @Test
    void capsOversizedTextAndMarksTruncation() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/file").queryParam("path", "big-thai.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Fsb-Truncated", "true"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("charset=UTF-8")))
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        // Capped at (or under, for char safety) the limit, and Content-Length is exact.
        assertThat(body.length).isLessThanOrEqualTo(FileController.TEXT_CAP_BYTES);
        assertThat(result.getResponse().getHeader("Content-Length"))
                .isEqualTo(String.valueOf(body.length));
        // 524288 % 3 == 2: the naive cut would split a Thai character. The
        // cut must have backed off to a character boundary, so the body
        // decodes strictly with zero replacement characters.
        assertThat(body.length).isEqualTo(FileController.TEXT_CAP_BYTES - 2);
        String decoded = FileController.decodeStrictly(body, StandardCharsets.UTF_8);
        assertThat(decoded).doesNotContain("\uFFFD");
        assertThat(decoded.chars().filter(c -> c == 'ฬ').count()).isEqualTo(body.length / 3L);
    }

    @Test
    void servesExactCapTextWithoutTruncationHeader() throws Exception {
        // A file of exactly cap bytes of spaces: no truncation, no header.
        Files.writeString(root.resolve("exact.txt"), " ".repeat(FileController.TEXT_CAP_BYTES));
        mockMvc.perform(get("/api/file").queryParam("path", "exact.txt"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Fsb-Truncated"))
                .andExpect(header().string("Content-Length", String.valueOf(FileController.TEXT_CAP_BYTES)));
    }

    @Test
    void servesPngByMagicInline() throws Exception {
        byte[] png = Files.readAllBytes(root.resolve("photo.png"));
        mockMvc.perform(get("/api/file").queryParam("path", "photo.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(header().string("Content-Length", String.valueOf(png.length)))
                .andExpect(content().bytes(png));
    }

    @Test
    void servesSvgInlineAsImage() throws Exception {
        mockMvc.perform(get("/api/file").queryParam("path", "icon.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("image/svg+xml")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")));
    }

    @Test
    void servesUnknownBinaryAsAttachmentWithFilename() throws Exception {
        byte[] binary = Files.readAllBytes(root.resolve("data.bin"));
        mockMvc.perform(get("/api/file").queryParam("path", "data.bin"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("attachment"),
                                org.hamcrest.Matchers.containsString("filename=\"data.bin\""))))
                .andExpect(content().bytes(binary));
    }

    @Test
    void encodesNonAsciiAttachmentFilenameRfc5987() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/file").queryParam("path", "日本語データ.bin"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andReturn();
        String disposition = result.getResponse().getHeader("Content-Disposition");
        // RFC 5987/6266: non-ASCII names travel in filename*=UTF-8''<pct-encoded>.
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains("filename*=UTF-8''");
        assertThat(disposition).contains(java.net.URLEncoder.encode("日本語データ.bin",
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
    }

    @Test
    void rejectsTraversal() throws Exception {
        mockMvc.perform(get("/api/file").queryParam("path", "../outside"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(URI.create("/api/file?path=%2e%2e%2f%2e%2e")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingFileIsNotFound() throws Exception {
        mockMvc.perform(get("/api/file").queryParam("path", "nope.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void directoryPathIsNotFound() throws Exception {
        mockMvc.perform(get("/api/file").queryParam("path", "somedir"))
                .andExpect(status().isNotFound());
    }

    @Test
    void omittedPathIsNotFound() throws Exception {
        // The Root itself is not a file.
        mockMvc.perform(get("/api/file"))
                .andExpect(status().isNotFound());
    }
}
