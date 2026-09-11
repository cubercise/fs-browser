package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.content.ContentSniffer;
import io.cubercise.fsbrowser.sandbox.Sandbox;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * File seam: serves exactly one file of the Root per request, classified by
 * leading content (never by extension). Three response shapes:
 * <ul>
 *   <li>Text — inline, charset detected (BOMs, strict UTF-8 validation, a
 *       conservative ISO-8859-1 printable heuristic; UTF-8 by default), body
 *       capped at {@link #TEXT_CAP_BYTES} with {@code X-Fsb-Truncated: true}
 *       when the cap bit, and the cut never splitting a multi-byte
 *       character or surrogate pair.</li>
 *   <li>Image (png/jpg/gif/webp magic, or SVG by text shape) — inline with
 *       the detected Content-Type, whole file.</li>
 *   <li>Anything else — attachment, application/octet-stream, RFC 5987/6266
 *       filename encoding for non-ASCII names, whole file.</li>
 * </ul>
 *
 * <p>SVG decision: SVG previews inline like any other image (that is its
 * point), served as image/svg+xml so browsers render it inside an
 * {@code <img>} — the scripting-capable document context an SVG wants before
 * it becomes interesting as an attack surface is not given to it there. Files
 * whose content is not confidently classifiable never render as pages; they
 * download. All path acceptance lives in the {@link Sandbox}; status codes
 * stay in the shared exception handler.
 *
 * <p>Bodies are byte[] in memory: exact Content-Length for free, trivially
 * testable — a fine trade for a local, read-only tool.
 */
@RestController
@RequestMapping("/api")
public class FileController {

    /**
     * Cap for inline text bodies. 512 KB is far beyond a screenful of text
     * yet cheap to decode per request. A constant on purpose: the wire
     * contract ({@code X-Fsb-Truncated}) must mean the same thing in every
     * deployment of one build.
     */
    static final int TEXT_CAP_BYTES = 512 * 1024;

    private final Sandbox sandbox;

    public FileController(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @GetMapping("/file")
    public ResponseEntity<byte[]> file(@RequestParam(value = "path", required = false) String path) {
        // No path = the Root itself, which is a directory → 404, consistent
        // with the tree endpoint's "empty path is the Root" convention.
        Path file = sandbox.resolveFile(path);
        byte[] head = readPrefix(file, ContentSniffer.SNIFF_WINDOW);
        // A full window means the file may continue past it (a second read
        // would be needed to prove EOF); a short read already proves EOF.
        boolean eof = head.length < ContentSniffer.SNIFF_WINDOW;
        ContentSniffer.Result sniff = ContentSniffer.sniff(head, !eof);
        String filename = file.getFileName().toString();

        HttpHeaders headers = new HttpHeaders();
        byte[] body;
        switch (sniff.kind()) {
            case TEXT -> {
                // A short head read means EOF: the head is the whole file.
                // cap+1 on the re-read: the extra byte is the evidence of truncation.
                byte[] candidate = head.length < ContentSniffer.SNIFF_WINDOW
                        ? head
                        : readPrefix(file, TEXT_CAP_BYTES + 1);
                boolean truncated = candidate.length > TEXT_CAP_BYTES;
                body = truncated
                        ? truncateSafely(candidate, TEXT_CAP_BYTES, sniff.charset())
                        : candidate;
                headers.setContentType(new MediaType("text", "plain", sniff.charset()));
                if (truncated) {
                    headers.set("X-Fsb-Truncated", "true");
                }
            }
            case IMAGE -> {
                body = readAll(file);
                headers.setContentType(MediaType.parseMediaType(sniff.mimeType()));
            }
            default -> {
                body = readAll(file);
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            }
        }
        // RFC 5987/6266: non-ASCII names go out as filename*=UTF-8''…, with
        // an ASCII fallback. For pure-ASCII names the plain filename="…"
        // form is used (forcing a charset would emit a =?UTF-8?Q?…?=
        // encoded-word no browser needs for ASCII).
        boolean asciiName = filename.chars().allMatch(c -> c < 0x80);
        var builder = sniff.kind() == ContentSniffer.Kind.BINARY
                ? ContentDisposition.attachment()
                : ContentDisposition.inline();
        if (asciiName) {
            builder.filename(filename);
        } else {
            builder.filename(filename, StandardCharsets.UTF_8);
        }
        headers.setContentDisposition(builder.build());
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /**
     * Cuts at most {@code cap} bytes off a text body so the result still
     * decodes strictly: UTF-8 backs over any continuation bytes of a
     * sequence straddling the cut; UTF-16 aligns to character units (keeping
     * any BOM) and drops a trailing lone high surrogate so pairs stay whole;
     * ISO-8859-1 is single-byte, any cut is aligned.
     */
    static byte[] truncateSafely(byte[] body, int cap, Charset charset) {
        int cut = Math.min(cap, body.length);
        if (charset.name().startsWith("UTF-16")) {
            int lead = hasUtf16Bom(body) ? 2 : 0;
            int payload = cut - lead;
            payload -= payload % 2; // align to a 2-byte character unit
            if (payload >= 2) {
                int first = body[lead + payload - 2] & 0xFF;
                int second = body[lead + payload - 1] & 0xFF;
                char last = bigEndian(body, charset)
                        ? (char) ((first << 8) | second)
                        : (char) ((second << 8) | first);
                if (Character.isHighSurrogate(last)) {
                    payload -= 2; // never split a surrogate pair
                }
            }
            cut = lead + payload;
        } else {
            while (cut > 0 && cut < body.length && (body[cut] & 0xC0) == 0x80) {
                cut--; // back over continuation bytes of a straddled sequence
            }
        }
        return Arrays.copyOf(body, cut);
    }

    private static boolean hasUtf16Bom(byte[] b) {
        return b.length >= 2
                && (((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
                || ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE));
    }

    private static boolean bigEndian(byte[] b, Charset charset) {
        return switch (charset.name()) {
            case "UTF-16LE" -> false;
            case "UTF-16BE" -> true;
            // "UTF-16": the BOM decides; no BOM defaults to big endian.
            default -> !(b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE);
        };
    }

    private static byte[] readAll(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + file, e);
        }
    }

    /** Reads at most {@code limit} bytes; short only at EOF. */
    private static byte[] readPrefix(Path file, int limit) {
        byte[] buffer = new byte[limit];
        int filled = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (filled < limit) {
                int n = in.read(buffer, filled, limit - filled);
                if (n < 0) {
                    break; // EOF (file may have shrunk since listing) — serve what's there
                }
                filled += n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + file, e);
        }
        return filled == limit ? buffer : Arrays.copyOf(buffer, filled);
    }

    /** Strict decode (REPORT, never REPLACE) — used by tests to prove cuts are clean. */
    static String decodeStrictly(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder();
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new UncheckedIOException(new IOException("Invalid bytes for " + charset, e));
        }
    }
}
