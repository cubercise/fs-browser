package io.cubercise.fsbrowser.content;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Content-based detection of what a file is, from its leading bytes only —
 * never from the filename/extension, which a client can lie about.
 *
 * <p>Dependency decision: Apache Tika core would do this more thoroughly, but
 * it drags in ~1 MB of jars (plus its own config) to answer a question this
 * app has exactly once, against a fixed menu (five image magics, SVG text
 * shape, BOMs, strict UTF-8 validation, one ISO-8859-1 heuristic). That is
 * not a sane trade for a single-endpoint tool, so this is hand-rolled and
 * deliberately conservative: anything not confidently text or a known image
 * falls through to BINARY and is served as an attachment.
 */
public final class ContentSniffer {

    /** How many leading bytes are inspected. SVG needs the most (text shape). */
    public static final int SNIFF_WINDOW = 8192;

    private ContentSniffer() {
    }

    public enum Kind {
        /** Serve inline as text/plain with the detected charset. */
        TEXT,
        /** Known raster image magic or SVG: serve with the detected image type. */
        IMAGE,
        /** Everything else: attachment, application/octet-stream. */
        BINARY
    }

    /**
     * @param mimeType full MIME type ("text/plain" for TEXT without charset —
     *                 the caller appends the charset; "image/png", … for IMAGE;
     *                 "application/octet-stream" for BINARY)
     */
    public record Result(Kind kind, String mimeType, Charset charset) {
        static Result text(Charset charset) {
            return new Result(Kind.TEXT, "text/plain", charset);
        }

        static Result image(String mimeType) {
            return new Result(Kind.IMAGE, mimeType, null);
        }

        static Result binary() {
            return new Result(Kind.BINARY, "application/octet-stream", null);
        }
    }

    /**
     * Classifies the leading bytes of a file. Order matters:
     * image magics first (they may contain byte patterns that also look like
     * text), then SVG's text shape, then BOMs, then strict UTF-8, then a
     * conservative ISO-8859-1 printable heuristic. An empty file is TEXT
     * (an empty preview is the truthful rendering).
     *
     * @param head the leading bytes
     * @param bytesBeyondWindow true when the file continues past {@code head}
     *                          (the sniff window cut it): a multi-byte
     *                          sequence straddling the window edge then stays
     *                          a valid-UTF-8 candidate. At EOF such a sequence
     *                          is simply invalid.
     */
    public static Result sniff(byte[] head, boolean bytesBeyondWindow) {
        if (head.length == 0) {
            return Result.text(StandardCharsets.UTF_8);
        }
        Result image = rasterImage(head);
        if (image != null) {
            return image;
        }
        if (looksLikeSvg(head)) {
            return Result.image("image/svg+xml");
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            // "UTF-16" (not UTF-16LE) so the decoder consumes the BOM itself.
            return Result.text(Charset.forName("UTF-16"));
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return Result.text(Charset.forName("UTF-16"));
        }
        Charset heuristicUtf16 = bomlessUtf16(head);
        if (heuristicUtf16 != null) {
            return Result.text(heuristicUtf16);
        }
        if (isValidUtf8(head, bytesBeyondWindow) && indexOf(head, 0, head.length, (byte) 0) < 0) {
            return Result.text(StandardCharsets.UTF_8);
        }
        if (isPrintableLatin1(head)) {
            return Result.text(StandardCharsets.ISO_8859_1);
        }
        return Result.binary();
    }

    /** The image types the preview pane can render: png/jpg/gif/webp. */
    private static Result rasterImage(byte[] b) {
        if (startsWith(b, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Result.image("image/png");
        }
        if (startsWith(b, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Result.image("image/jpeg");
        }
        if (startsWith(b, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(b, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return Result.image("image/gif");
        }
        // WebP: "RIFF" <4-byte size> "WEBP".
        if (startsWith(b, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && b.length >= 12
                && startsAt(b, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return Result.image("image/webp");
        }
        return null;
    }

    /**
     * SVG is text, so it is recognised by shape: after an optional XML/BOM
     * prologue the document must open with an {@code <svg} or
     * {@code <!DOCTYPE svg} tag within the sniff window. Requiring the tag up
     * front (not merely anywhere) keeps ordinary text/HTML mentioning
     * "&lt;svg" out.
     */
    private static boolean looksLikeSvg(byte[] b) {
        String s = new String(b, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int i = 0;
        // Skip a UTF-8/UTF-16 BOM if present.
        if (s.startsWith("\uFEFF")) {
            i = 1;
        }
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        String rest = s.substring(i);
        if (rest.startsWith("<svg") || rest.startsWith("<!doctype svg")) {
            return true;
        }
        // "<?xml … ?>" prologue followed by <svg within the window.
        return rest.startsWith("<?xml") && rest.indexOf("<svg") >= 0;
    }

    /**
     * BOM-less UTF-16 heuristic: ASCII-heavy UTF-16 has a NUL on every other
     * byte. Even offsets NUL → BE, odd offsets NUL → LE. Rare for real
     * binaries, and a wrong guess only degrades the preview.
     */
    private static Charset bomlessUtf16(byte[] b) {
        if (b.length < 4) {
            return null;
        }
        int sample = Math.min(b.length, 256);
        boolean evenNul = true;
        boolean oddNul = true;
        for (int i = 0; i < sample; i++) {
            boolean nul = b[i] == 0;
            evenNul &= (i % 2 == 0) ? nul : !nul;
            oddNul &= (i % 2 == 1) ? nul : !nul;
        }
        if (evenNul) {
            return StandardCharsets.UTF_16BE;
        }
        if (oddNul) {
            return StandardCharsets.UTF_16LE;
        }
        return null;
    }

    /** Strict UTF-8 validation of the whole window. */
    private static boolean isValidUtf8(byte[] b, boolean bytesBeyondWindow) {
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                i++;
                continue;
            }
            int extra;
            int minCodePoint;
            if ((c & 0xE0) == 0xC0) {
                extra = 1;
                minCodePoint = 0x80;
            } else if ((c & 0xF0) == 0xE0) {
                extra = 2;
                minCodePoint = 0x800;
            } else if ((c & 0xF8) == 0xF0) {
                extra = 3;
                minCodePoint = 0x10000;
            } else {
                return false; // continuation byte in lead position or 0xF8+
            }
            if (i + extra >= b.length) {
                // Sequence cut off at the end of the buffer: only when more
                // file bytes follow (window cut) is this a plausible lead-in
                // that keeps the UTF-8 candidacy alive; at EOF it is simply
                // invalid, and the file falls through to the Latin-1 test.
                return bytesBeyondWindow;
            }
            int codePoint = c & (0x3F >> extra); // lead byte's data bits
            i++;
            for (int j = 0; j < extra; j++) {
                int cc = b[i + j] & 0xFF;
                if ((cc & 0xC0) != 0x80) {
                    return false;
                }
                codePoint = (codePoint << 6) | (cc & 0x3F);
            }
            if (codePoint < minCodePoint || codePoint > 0x10FFFF
                    || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                return false; // overlong / surrogate / out of range
            }
            i += extra;
        }
        return true;
    }

    /** Conservative ISO-8859-1 text check: CRLF/Tab, printable ASCII, 0xA0–0xFF. */
    private static boolean isPrintableLatin1(byte[] b) {
        for (byte x : b) {
            int c = x & 0xFF;
            boolean ok = c == 0x09 || c == 0x0A || c == 0x0D
                    || (c >= 0x20 && c <= 0x7E)
                    || (c >= 0xA0);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        return startsAt(b, 0, prefix);
    }

    private static boolean startsAt(byte[] b, int offset, byte[] prefix) {
        if (b.length - offset < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] b, int from, int to, byte value) {
        for (int i = from; i < to; i++) {
            if (b[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
