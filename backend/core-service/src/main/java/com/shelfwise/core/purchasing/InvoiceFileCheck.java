package com.shelfwise.core.purchasing;

import java.nio.charset.StandardCharsets;

/**
 * Decides what an uploaded file really is by reading its bytes, never by trusting its name or the type the
 * browser claims (DL-04). The dataset's traps: a Windows program renamed to .pdf, a PDF cut off part-way,
 * and a file over 20 MB.
 */
public final class InvoiceFileCheck {

    public static final int MAX_BYTES = 20 * 1024 * 1024;

    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PNG_END = {'I', 'E', 'N', 'D', (byte) 0xAE, 'B', 0x60, (byte) 0x82};

    /** Why a file was refused: TOO_LARGE maps to HTTP 413, UNSUPPORTED_TYPE to 415, the others to 422. */
    public enum Problem { EMPTY, TOO_LARGE, UNSUPPORTED_TYPE, CORRUPT }

    public record Result(String contentType, Problem problem, String message) {
        public boolean ok() {
            return problem == null;
        }
    }

    private InvoiceFileCheck() {
    }

    public static Result check(byte[] f) {
        if (f.length == 0) {
            return bad(Problem.EMPTY, "The file is empty");
        }
        if (f.length > MAX_BYTES) {
            return bad(Problem.TOO_LARGE, "The file is over the 20 MB limit");
        }
        if (startsWith(f, PDF)) {
            // A complete PDF ends with %%EOF (trailing newlines or padding allowed within the last KB).
            int from = Math.max(0, f.length - 1024);
            String tail = new String(f, from, f.length - from, StandardCharsets.ISO_8859_1);
            return tail.contains("%%EOF") ? good("application/pdf")
                    : bad(Problem.CORRUPT, "The PDF is damaged or incomplete (it has no end marker)");
        }
        if (startsWith(f, PNG)) {
            return endsWith(f, PNG_END) ? good("image/png")
                    : bad(Problem.CORRUPT, "The PNG image is damaged or incomplete");
        }
        if (f.length >= 4 && (f[0] & 0xFF) == 0xFF && (f[1] & 0xFF) == 0xD8 && (f[2] & 0xFF) == 0xFF) {
            return (f[f.length - 2] & 0xFF) == 0xFF && (f[f.length - 1] & 0xFF) == 0xD9 ? good("image/jpeg")
                    : bad(Problem.CORRUPT, "The JPEG image is damaged or incomplete");
        }
        return bad(Problem.UNSUPPORTED_TYPE,
                "Only PDF, PNG or JPEG files are accepted (checked by the file's contents, not its name)");
    }

    private static Result good(String contentType) {
        return new Result(contentType, null, null);
    }

    private static Result bad(Problem problem, String message) {
        return new Result(null, problem, message);
    }

    private static boolean startsWith(byte[] f, byte[] prefix) {
        if (f.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (f[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(byte[] f, byte[] suffix) {
        if (f.length < suffix.length) {
            return false;
        }
        for (int i = 0; i < suffix.length; i++) {
            if (f[f.length - suffix.length + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }
}
