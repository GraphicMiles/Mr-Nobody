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
     * The size of the finished file. {@code Content-Length} on a 206 is the
     * length of the <em>remainder</em>, not of the file, which is the classic
     * way a resumed download reports 8% forever.
     */
    static long totalSize(int statusCode, long from, long contentLength) {
        if (contentLength < 0) return DownloadRecord.UNKNOWN_SIZE;
        return statusCode == PARTIAL ? from + contentLength : contentLength;
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
