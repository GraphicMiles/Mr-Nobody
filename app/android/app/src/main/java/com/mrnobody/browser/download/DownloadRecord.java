package com.mrnobody.browser.download;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One download, as the app knows it.
 *
 * <p>This exists because Mr Nobody stopped handing files to Android's
 * {@code DownloadManager}. That service could not write into a folder the user
 * picked, could not be paused, and posted its own notification with its own
 * name on it — so a film the user asked to save to their Movies folder landed
 * in app-private staging under a system notification, which is exactly what
 * they saw. Owning the transfer means owning its state, and this is it.
 */
public final class DownloadRecord {

    /** What a download is doing. Persisted as a string: readable in a dump. */
    public enum Status {
        /** Accepted, waiting for a worker thread. */
        QUEUED,
        /** Bytes are moving. */
        RUNNING,
        /** Stopped by the user; the partial file and its offset are kept. */
        PAUSED,
        /** Stopped by something else (no network, server refused). Resumable. */
        WAITING,
        /** All bytes written and the file published where the user can see it. */
        COMPLETED,
        /** Gave up. {@link #error} says why. */
        FAILED,
        /** The user cancelled; the partial file has been deleted. */
        CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }

        /** Whether the user can meaningfully press Resume. */
        public boolean isResumable() {
            return this == PAUSED || this == WAITING || this == FAILED;
        }

        public boolean isActive() {
            return this == QUEUED || this == RUNNING;
        }

        static Status of(@Nullable String raw) {
            if (raw == null) return QUEUED;
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException e) {
                return QUEUED;
            }
        }
    }

    /** Total size is unknown until (and unless) the server says so. */
    public static final long UNKNOWN_SIZE = -1L;

    public long id;
    public String url;
    public String fileName;
    @Nullable public String mime;
    @Nullable public String userAgent;
    @Nullable public String referrer;

    /**
     * Where the bytes are being written — the document in the user's chosen
     * folder, or the MediaStore entry in public Downloads. Null until the
     * first response, because the server's headers are what decide the final
     * name and type.
     */
    @Nullable public String destUri;

    /** The folder as a person would name it, for the UI. */
    @Nullable public String destLabel;

    public long total = UNKNOWN_SIZE;
    public long bytes;

    public Status status = Status.QUEUED;
    @Nullable public String error;

    /** Validator for a range request, so a resume cannot splice two files. */
    @Nullable public String etag;

    /** Whether the server honoured (or advertised) byte ranges. */
    public boolean resumable;

    /** User/policy explicitly approved executable or otherwise risky content. */
    public boolean riskyApproved;

    public long createdAt;
    public long updatedAt;

    public DownloadRecord() {
    }

    public static DownloadRecord create(@NonNull String url, @NonNull String fileName,
                                        @Nullable String mime, @Nullable String userAgent,
                                        @Nullable String referrer, @Nullable String destLabel) {
        return create(url, fileName, mime, userAgent, referrer, destLabel, false);
    }

    public static DownloadRecord create(@NonNull String url, @NonNull String fileName,
                                        @Nullable String mime, @Nullable String userAgent,
                                        @Nullable String referrer, @Nullable String destLabel,
                                        boolean riskyApproved) {
        DownloadRecord r = new DownloadRecord();
        r.url = url;
        r.fileName = fileName;
        r.mime = mime;
        r.userAgent = userAgent;
        r.referrer = referrer;
        r.destLabel = destLabel;
        r.riskyApproved = riskyApproved;
        r.status = Status.QUEUED;
        r.createdAt = System.currentTimeMillis();
        r.updatedAt = r.createdAt;
        return r;
    }

    /** 0..100, or -1 when the server never said how big the file is. */
    public int percent() {
        if (total <= 0) return -1;
        long pct = bytes * 100L / total;
        return (int) Math.max(0, Math.min(100, pct));
    }

    /**
     * What the Downloads screen and the notification both read. Kept in one
     * place so the two can never disagree about a download's state.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("url", url);
        m.put("name", fileName);
        m.put("mime", mime);
        m.put("size", total);
        m.put("downloaded", bytes);
        m.put("status", status.name());
        m.put("percent", percent());
        m.put("error", error);
        m.put("resumable", resumable);
        m.put("riskApproved", riskyApproved);
        m.put("canResume", status.isResumable());
        m.put("localUri", destUri);
        m.put("folder", destLabel);
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        return m;
    }
}
