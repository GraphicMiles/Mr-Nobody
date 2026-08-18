package com.mrnobody.browser.download;

import androidx.annotation.Nullable;

/**
 * The rules for continuing a download that stopped.
 *
 * <p>Resume is the part of a downloader that quietly corrupts files when it is
 * wrong: append to a file the server has since replaced and you get a valid
 * number of bytes that are not a valid film. So the decisions live here as
 * plain functions over a status code and a couple of headers, where they can
 * be tested without a network, a device, or a four-gigabyte fixture.
 */
final class DownloadResume {

    /** HTTP 206: the server honoured our range and is sending the rest. */
    static final int PARTIAL = 206;
    static final int OK = 200;
    static final int RANGE_NOT_SATISFIABLE = 416;

    private DownloadResume() {
    }

    /** The header that asks for everything from {@code from} onwards. */
    static String rangeHeader(long from) {
        return "bytes=" + from + "-";
    }

    /**
     * Whether the transfer has to start again from zero.
     *
     * <p>True when we asked to continue and the server answered with a whole
     * new body (200) instead of a range (206) — it either ignored the header
     * or, because of {@code If-Range}, is telling us the file changed. Either
     * way the bytes on disk are from a different document and appending to
     * them would splice two files together.
     */
    static boolean mustRestart(int statusCode, long requestedFrom) {
        return requestedFrom > 0 && statusCode == OK;
    }

    /** Whether this response is one we can write bytes from at all. */
    static boolean isUsable(int statusCode) {
        return statusCode == OK || statusCode == PARTIAL;
    }

    /**
     * Whether a future pause could be resumed. A 206 proves it; otherwise the
     * server has to have advertised it.
     */
    static boolean supportsRanges(int statusCode, @Nullable String acceptRanges) {
        if (statusCode == PARTIAL) return true;
        return acceptRanges != null && acceptRanges.trim().equalsIgnoreCase("bytes");
    }

    /**
     * The size of the finished file, inferred from the status code alone.
     *
     * <p>{@code Content-Length} on a 206 is the length of the
     * <em>remainder</em>, not of the file, which is the classic way a resumed
     * download reports 8% forever.
     *
     * <p>Prefer {@link #totalSize(int, long, long, String)}: this arithmetic
     * is only correct when the server sent exactly the range we asked for.
     */
    static long totalSize(int statusCode, long from, long contentLength) {
        if (contentLength < 0) return DownloadRecord.UNKNOWN_SIZE;
        return statusCode == PARTIAL ? from + contentLength : contentLength;
    }

    /**
     * The size of the finished file, preferring what the server stated.
     *
     * <p>{@code Content-Range: bytes 12582912-31457279/31457280} carries the
     * total after the slash, and that number is authoritative: it is the
     * server describing the whole document rather than us adding our offset
     * to the length of a body. The two only agree when the server honoured
     * precisely the range we asked for, and plenty of CDNs do not -- some
     * answer a range request with the length of the entire file, and then
     * {@code from + contentLength} counts the prefix twice. A 30 MB file
     * resumed at 12 MB is reported as 42 MB, and the bar sits under half
     * while the last byte lands. That is the bug this exists to remove.
     */
    static long totalSize(int statusCode, long from, long contentLength,
                          @Nullable String contentRange) {
        long stated = statedTotal(contentRange);
        if (stated > 0) return stated;
        return totalSize(statusCode, from, contentLength);
    }

    /**
     * The total from a {@code Content-Range} header, or -1.
     *
     * <p>A server that does not know the length sends {@code /*}, which is an
     * honest "unknown" and must not be read as a number.
     */
    static long statedTotal(@Nullable String contentRange) {
        if (contentRange == null) return DownloadRecord.UNKNOWN_SIZE;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1) {
            return DownloadRecord.UNKNOWN_SIZE;
        }
        String tail = contentRange.substring(slash + 1).trim();
        if (tail.isEmpty() || "*".equals(tail)) return DownloadRecord.UNKNOWN_SIZE;
        try {
            long total = Long.parseLong(tail);
            return total > 0 ? total : DownloadRecord.UNKNOWN_SIZE;
        } catch (NumberFormatException e) {
            return DownloadRecord.UNKNOWN_SIZE;
        }
    }

    /**
     * Whether {@code Content-Length} describes the bytes we are going to
     * count.
     *
     * <p>It does not when the body is compressed in transit. HttpURLConnection
     * asks for gzip on our behalf unless told otherwise and decompresses
     * transparently, so the header measures the compressed body while the
     * stream hands us the expanded one. Comparing the two produces a
     * percentage of two different quantities: the bar races past the end on a
     * compressible file, or crawls, depending on which way the ratio falls.
     *
     * <p>The download path asks for {@code identity} to keep the two the same
     * measurement. If a server ignores that and compresses anyway, the honest
     * answer is that the size is unknown -- an indeterminate bar tells the
     * truth, and a number computed from mismatched units does not.
     */
    static boolean lengthDescribesTheStream(@Nullable String contentEncoding) {
        if (contentEncoding == null) return true;
        String encoding = contentEncoding.trim();
        return encoding.isEmpty() || encoding.equalsIgnoreCase("identity");
    }

    /**
     * The strongest validator the server gave us, for {@code If-Range}. An
     * ETag is exact; a Last-Modified date is a second-resolution guess, but it
     * is better than appending blind.
     */
    @Nullable
    static String validator(@Nullable String etag, @Nullable String lastModified) {
        if (etag != null && !etag.trim().isEmpty()) return etag.trim();
        if (lastModified != null && !lastModified.trim().isEmpty()) return lastModified.trim();
        return null;
    }

    /** What to tell the user when the server says no. */
    static String message(int code, @Nullable String reason) {
        switch (code) {
            case 401:
            case 403:
                return "The server refused this download (" + code + ")";
            case 404:
                return "The file is no longer there (404)";
            case RANGE_NOT_SATISFIABLE:
                return "The server could not continue this download";
            case 500:
            case 502:
            case 503:
                return "The server is having trouble (" + code + ") — try again";
            default:
                return "Server returned " + code
                        + (reason == null || reason.isEmpty() ? "" : " " + reason);
        }
    }
}
