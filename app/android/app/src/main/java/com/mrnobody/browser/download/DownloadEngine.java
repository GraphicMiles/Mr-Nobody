package com.mrnobody.browser.download;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.agent.util.NetworkTargetPolicy;
import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.debug.ErrorLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Mr Nobody's own download engine.
 *
 * <p>Replaces Android's {@code DownloadManager}, which was the wrong tool for
 * this product in four ways the user hit directly: it cannot write into a
 * folder chosen through the Storage Access Framework, it has no pause, it
 * posts a system notification the app cannot shape, and its transfers keep
 * running after the app is uninstalled because they belong to the system, not
 * to us. Doing the transfer in-process fixes all four — and a paused download
 * is genuinely paused, because we are the ones holding the socket.
 *
 * <p>Resume is a real HTTP range request validated with {@code If-Range}, so a
 * file that changed on the server restarts cleanly instead of being spliced
 * together from two different versions.
 */
public final class DownloadEngine {

    /** Two at a time: enough to feel parallel, few enough to keep each fast. */
    private static final int MAX_PARALLEL = 2;

    private static final int BUFFER = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Progress is persisted and redrawn at most this often. */
    private static final long TICK_MS = 700;

    private static volatile DownloadEngine instance;

    /** Told about every state change: the service turns these into UI. */
    public interface Listener {
        void onChanged(@NonNull DownloadRecord record);
    }

    private final Context context;
    private final DownloadStore store;
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL);
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Draws notifications for the whole life of the engine.
     *
     * <p>Deliberately not the service. The service stops itself when no
     * download is active, and the change that makes a download inactive is the
     * completion itself -- so a service-owned renderer is being torn down at
     * the exact moment it has to draw "finished", and sometimes never draws
     * it. Holding the renderer here means a completion is posted by something
     * that is still alive.
     */
    private final DownloadNotifier notifier;

    private DownloadEngine(Context context) {
        this.context = context.getApplicationContext();
        this.store = DownloadStore.get(this.context);
        this.notifier = new DownloadNotifier(this.context);
        addListener(this.notifier);
    }

    public static DownloadEngine get(@NonNull Context context) {
        if (instance == null) {
            synchronized (DownloadEngine.class) {
                if (instance == null) instance = new DownloadEngine(context);
            }
        }
        return instance;
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    public DownloadStore store() {
        return store;
    }

    /** Whether anything is still moving — the service stops when nothing is. */
    public boolean hasActiveWork() {
        for (Job job : jobs.values()) {
            if (!job.finished) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- commands

    /**
     * Block until {@code id} reaches a terminal status, or {@code timeoutMs}
     * elapses. Used by the agent: "downloaded" must mean COMPLETED, not queued.
     *
     * @return the latest record, which may still be running if time ran out
     */
    @Nullable
    public DownloadRecord awaitTerminal(long id, long timeoutMs) {
        return awaitTerminal(id, timeoutMs, () -> false);
    }

    /** Cancellable wait; cancellation or interruption also stops the transfer. */
    @Nullable
    public DownloadRecord awaitTerminal(long id, long timeoutMs,
                                        java.util.function.BooleanSupplier cancelled) {
        long deadline = System.currentTimeMillis() + Math.max(250L, timeoutMs);
        DownloadRecord latest = store.find(id);
        while (System.currentTimeMillis() < deadline) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                cancel(id);
                return store.find(id);
            }
            latest = store.find(id);
            if (latest == null) return null;
            if (latest.status != null && latest.status.isTerminal()) return latest;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                cancel(id);
                Thread.currentThread().interrupt();
                return store.find(id);
            }
        }
        return latest;
    }

    /**
     * Accept a new download. Returns immediately with a persisted record, so
     * the UI can show the row before a single byte has arrived.
     */
    public DownloadRecord enqueue(@NonNull String url, @NonNull String fileName,
                                  @Nullable String mime, @Nullable String userAgent,
                                  @Nullable String referrer) {
        return enqueue(url, fileName, mime, userAgent, referrer, false);
    }

    public DownloadRecord enqueue(@NonNull String url, @NonNull String fileName,
                                  @Nullable String mime, @Nullable String userAgent,
                                  @Nullable String referrer, boolean riskyApproved) {
        return enqueue(url, fileName, mime, userAgent, referrer, riskyApproved,
                false, null);
    }

    /** Enqueue once for a durable harness idempotency key. */
    public DownloadRecord enqueue(@NonNull String url, @NonNull String fileName,
                                  @Nullable String mime, @Nullable String userAgent,
                                  @Nullable String referrer, boolean riskyApproved,
                                  @Nullable String requestKey) {
        return enqueue(url, fileName, mime, userAgent, referrer, riskyApproved,
                false, requestKey);
    }

    /**
     * Full enqueue. {@code allowInsecureReferer} is set only when the user
     * explicitly approved a cleartext download that carries a page Referer; by
     * default a referrer is never sent over plain HTTP (see {@link #connect}).
     */
    public DownloadRecord enqueue(@NonNull String url, @NonNull String fileName,
                                  @Nullable String mime, @Nullable String userAgent,
                                  @Nullable String referrer, boolean riskyApproved,
                                  boolean allowInsecureReferer,
                                  @Nullable String requestKey) {
        String key = requestKey == null ? null : requestKey.trim();
        if (key != null && !key.isEmpty()) {
            DownloadRecord existing = store.findByRequestKey(key);
            if (existing != null) return existing;
        }
        DownloadDestination destination = new DownloadDestination(context);
        DownloadRecord record = DownloadRecord.create(
                url, fileName, mime, userAgent, referrer, destination.label(),
                riskyApproved, allowInsecureReferer, key);
        long inserted = store.insert(record);
        if (inserted < 0 && key != null && !key.isEmpty()) {
            DownloadRecord existing = store.findByRequestKey(key);
            if (existing != null) return existing;
        }
        if (inserted < 0) throw new IllegalStateException("could not persist download");
        // Register the job before publishing QUEUED. A listener can act on that
        // first event immediately; it must find the job and set its stop flag
        // before the pool is allowed to start it.
        Job job = prepare(record);
        publish(record);
        DownloadService.wake(context);
        start(job);
        return record;
    }

    /**
     * Stop a running download but keep everything needed to continue it: the
     * bytes on disk, the offset, and the validator for the range request.
     */
    public boolean pause(long id) {
        Job job = jobs.get(id);
        if (job != null && job.requestPause()) {
            job.cancelStream();
            return true;
        }
        // Not running yet (still queued): park it without ever starting.
        DownloadRecord record = store.find(id);
        if (record == null || record.status.isTerminal()) return false;
        record.status = DownloadRecord.Status.PAUSED;
        store.update(record);
        publish(record);
        return true;
    }

    /** Continue a paused, stalled or failed download from where it stopped. */
    public boolean resume(long id) {
        DownloadRecord record = store.find(id);
        if (record == null) return false;
        if (record.status.isActive() || jobs.containsKey(id)) return true;
        if (record.status == DownloadRecord.Status.COMPLETED) return false;
        record.status = DownloadRecord.Status.QUEUED;
        record.error = null;
        store.update(record);
        Job job = prepare(record);
        publish(record);
        DownloadService.wake(context);
        start(job);
        return true;
    }

    /** Stop for good and delete the partial file. */
    public boolean cancel(long id) {
        Job job = jobs.get(id);
        if (job != null && job.requestCancel()) {
            job.cancelStream();
        }
        DownloadRecord record = store.find(id);
        if (record == null) return false;
        if (record.status != DownloadRecord.Status.COMPLETED) {
            DownloadSink.discard(context, record.destUri);
            record.destUri = null;
            record.bytes = 0;
            record.status = DownloadRecord.Status.CANCELLED;
            store.update(record);
            publish(record);
        }
        return true;
    }

    /**
     * Remove the download from the list. Deletes the file too unless the user
     * only wants the row gone.
     */
    public boolean remove(long id, boolean deleteFile) {
        cancelQuietly(id);
        DownloadRecord record = store.find(id);
        if (record == null) return false;
        if (deleteFile) DownloadSink.discard(context, record.destUri);
        store.delete(id);
        record.status = DownloadRecord.Status.CANCELLED;
        publish(record);
        return true;
    }

    /**
     * After a process death nothing is running, whatever the database says.
     * Park those rows as stalled so the user sees a Resume button rather than
     * a progress bar that will never move again.
     */
    public void reconcile() {
        // Clear notifications for work that died with the last process before
        // republishing anything: a progress bar nothing will ever update again
        // is a claim about right now that is false.
        notifier.reconcile();
        for (DownloadRecord record : store.active()) {
            if (jobs.containsKey(record.id)) continue;
            record.status = DownloadRecord.Status.WAITING;
            record.error = "Interrupted";
            store.update(record);
            publish(record);
        }
    }

    private void cancelQuietly(long id) {
        Job job = jobs.get(id);
        if (job != null && job.requestCancel()) {
            job.cancelStream();
        }
    }

    // ------------------------------------------------------------- plumbing

    private Job prepare(DownloadRecord record) {
        Job job = new Job(record);
        jobs.put(record.id, job);
        return job;
    }

    private void start(Job job) {
        job.future = pool.submit(job);
    }

    private void publish(DownloadRecord record) {
        for (Listener listener : listeners) {
            try {
                listener.onChanged(record);
            } catch (Throwable t) {
                ErrorLog.record("download listener failed: " + t);
            }
        }
    }

    /** One transfer, start to finish, on a pool thread. */
    private final class Job implements Runnable {

        private final DownloadRecord record;
        volatile boolean pauseRequested;
        volatile boolean cancelRequested;
        volatile boolean finished;
        volatile Future<?> future;
        private boolean completionCommitted;
        private volatile HttpURLConnection connection;

        Job(DownloadRecord record) {
            this.record = record;
        }

        /** Claim cancellation unless completion already committed atomically. */
        synchronized boolean requestCancel() {
            if (completionCommitted || finished) return false;
            cancelRequested = true;
            return true;
        }

        /** Claim a pause unless completion already committed atomically. */
        synchronized boolean requestPause() {
            if (completionCommitted || finished || cancelRequested) return false;
            pauseRequested = true;
            return true;
        }

        /**
         * Break the read that is blocking on the socket. Pausing has to be
         * immediate to be believable — waiting for a 30-second read timeout
         * would look exactly like a button that does nothing.
         */
        void cancelStream() {
            HttpURLConnection open = connection;
            if (open != null) {
                try {
                    open.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void run() {
            try {
                transfer();
            } catch (Throwable t) {
                // disconnect() is how pause/cancel breaks a connect or read.
                // Never let that expected exception overwrite the user's
                // terminal state with FAILED.
                if (cancelRequested) {
                    // cancel() already persisted CANCELLED and removed bytes.
                } else if (pauseRequested) {
                    park(record.bytes);
                } else {
                    fail(friendly(t));
                }
            } finally {
                finished = true;
                jobs.remove(record.id);
            }
        }

        private void transfer() throws IOException {
            // A queued job can be cancelled or paused before its pool thread
            // starts. Serialize that decision with the RUNNING transition so
            // this worker cannot resurrect a terminal database row.
            synchronized (this) {
                if (cancelRequested) return;
                if (pauseRequested) {
                    park(record.bytes);
                    return;
                }
                record.status = DownloadRecord.Status.RUNNING;
                record.error = null;
                store.update(record);
                publish(record);
            }

            DownloadSink sink = openSink();
            // Trust the file, not the database: if a previous run died between
            // writing bytes and recording them, the file length is the truth.
            long from = sink == null ? 0 : sink.size();
            if (from > 0 && (record.etag == null || record.etag.trim().isEmpty())) {
                // Appending without an ETag/Last-Modified validator can splice
                // a replaced remote file onto an old prefix. Sacrifice the
                // resume rather than the file's integrity.
                DownloadSink.discard(context, record.destUri);
                record.destUri = null;
                record.bytes = 0;
                record.total = DownloadRecord.UNKNOWN_SIZE;
                sink = null;
                from = 0;
                store.update(record);
            }

            Connected connected = connect(from);
            HttpURLConnection conn = connected.connection;
            this.connection = conn;
            try {
            int code = conn.getResponseCode();

            if (!DownloadResume.isUsable(code)) {
                throw new IOException(DownloadResume.message(code, conn.getResponseMessage()));
            }
            if (DownloadResume.mustRestart(code, from)) {
                // The server ignored the range, or If-Range told us the file
                // changed. Start over rather than glue two documents together.
                from = 0;
            }

            record.resumable = DownloadResume.supportsRanges(
                    code, conn.getHeaderField("Accept-Ranges"));
            String responseEtag = conn.getHeaderField("ETag");
            String responseLastModified = conn.getHeaderField("Last-Modified");
            String validator = DownloadResume.validator(responseEtag, responseLastModified);

            // Content-Range first: the server stating the whole size beats us
            // inferring it by adding our offset to a body length, which
            // double-counts whenever a CDN answers a range with the full file.
            String contentRange = conn.getHeaderField("Content-Range");
            if (from > 0 && code == DownloadResume.PARTIAL) {
                if (!DownloadResume.startsAt(contentRange, from)) {
                    throw new IOException("Server returned a different byte range; partial file was not changed");
                }
                if (!DownloadResume.responseMatchesValidator(
                        record.etag, responseEtag, responseLastModified)) {
                    throw new IOException("The remote file changed; partial file was not changed");
                }
            }
            // Do not replace the validator until the response has proved safe
            // to append. Persisting a mismatched new validator beside an old
            // prefix would make the next retry corrupt the file.
            if (validator != null) record.etag = validator;
            if (DownloadResume.lengthDescribesTheStream(conn.getContentEncoding())) {
                record.total = DownloadResume.totalSize(
                        code, from, contentLength(conn), contentRange);
            } else {
                // A compressed body measures a different thing than the bytes
                // we write. Say unknown and show an indeterminate bar rather
                // than a confident percentage of the wrong denominator.
                long stated = DownloadResume.statedTotal(contentRange);
                record.total = stated > 0 ? stated : DownloadRecord.UNKNOWN_SIZE;
            }

            // Now — and only now — final redirects and response headers tell
            // us what this file really is. Re-run the executable risk gate
            // before opening a destination or writing one byte.
            refineMetadata(conn, connected.url);
            DownloadRisk.Assessment finalRisk = DownloadRisk.assess(
                    record.fileName, record.mime, connected.url);
            if (finalRisk.requiresConfirmation && !record.riskyApproved) {
                throw new IOException("Download changed to a risky file: " + finalRisk.reason);
            }
            if (sink == null) {
                sink = createSink();
                record.destUri = sink.uri().toString();
            }
            record.bytes = from;
            store.update(record);
            publish(record);
            } catch (Throwable t) {
                this.connection = null;
                try { conn.disconnect(); } catch (Throwable ignored) { }
                if (t instanceof IOException) throw (IOException) t;
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                if (t instanceof Error) throw (Error) t;
                throw new IOException(t);
            }

            long written = from;
            long lastTick = 0;
            byte[] buffer = new byte[BUFFER];

            try (InputStream in = conn.getInputStream();
                 OutputStream out = sink.open(from > 0)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelRequested) return;      // cancel() cleans up
                    if (pauseRequested) {
                        out.flush();
                        park(written);
                        return;
                    }
                    out.write(buffer, 0, read);
                    written += read;

                    long now = System.currentTimeMillis();
                    if (now - lastTick >= TICK_MS) {
                        lastTick = now;
                        record.bytes = written;
                        store.updateProgress(record.id, written, record.total);
                        publish(record);
                    }
                }
                out.flush();
            } catch (IOException e) {
                // A disconnect we asked for is a pause, not a failure.
                if (cancelRequested) return;
                if (pauseRequested) {
                    park(written);
                    return;
                }
                record.bytes = written;
                store.update(record);
                throw e;
            } finally {
                this.connection = null;
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }

            if (cancelRequested) return;
            if (pauseRequested) {
                park(written);
                return;
            }
            if (!DownloadResume.isComplete(written, record.total)) {
                record.bytes = written;
                store.update(record);
                throw new IOException("Download ended after " + written
                        + " bytes; expected " + record.total);
            }

            // Serialize the final publish with pause/cancel. Whichever claims
            // the job first wins: a completed file cannot be cancelled as a
            // partial, and a claimed cancellation cannot be resurrected as
            // COMPLETED a few instructions later.
            synchronized (this) {
                if (cancelRequested) return;
                if (pauseRequested) {
                    park(written);
                    return;
                }
                sink.publish();
                record.bytes = written;
                if (record.total <= 0) record.total = written;
                record.status = DownloadRecord.Status.COMPLETED;
                record.error = null;
                store.update(record);
                publish(record);
                completionCommitted = true;
            }
        }

        /** Stop cleanly, keeping the offset so Resume can pick it up. */
        private void park(long written) {
            record.bytes = written;
            record.status = DownloadRecord.Status.PAUSED;
            record.error = null;
            store.update(record);
            publish(record);
        }

        private void fail(String message) {
            record.status = DownloadRecord.Status.FAILED;
            record.error = message;
            store.update(record);
            publish(record);
            ErrorLog.record("download failed: " + record.fileName + " — " + message);
        }

        @Nullable
        private DownloadSink openSink() {
            if (record.destUri == null || record.destUri.isEmpty()) return null;
            DownloadSink sink = DownloadSink.existing(context, Uri.parse(record.destUri));
            // A destination the user deleted underneath us is not a resume.
            return sink.size() > 0 || record.bytes == 0 ? sink : null;
        }

        private void refineMetadata(HttpURLConnection conn, String responseUrl) {
            String disposition = conn.getHeaderField("Content-Disposition");
            String type = conn.getContentType();
            // The final URL/headers beat anything guessed from the original
            // link: this is what stops an .mkv being saved as downloadfile.bin.
            String name = DownloadNaming.fileName(responseUrl, disposition,
                    type != null ? type : record.mime);
            if (name != null && !name.isEmpty()) record.fileName = name;
            if (type != null && !type.isEmpty()) {
                int semi = type.indexOf(';');
                record.mime = (semi > 0 ? type.substring(0, semi) : type).trim();
            }
        }

        private DownloadSink createSink() throws IOException {
            DownloadDestination destination = new DownloadDestination(context);
            Uri tree = destination.treeUri();
            record.destLabel = destination.label();
            return DownloadSink.create(context, tree, record.fileName, record.mime);
        }

        private final class Connected {
            final HttpURLConnection connection;
            final String url;

            Connected(HttpURLConnection connection, String url) {
                this.connection = connection;
                this.url = url;
            }
        }

        /** Follow redirects manually so every hop is checked and credentials are re-scoped. */
        private Connected connect(long from) throws IOException {
            String current = record.url;
            boolean sendReferrer = true;
            // Both schemes reach here. A public http(s) target may be fetched —
            // the user chose it in the visible browser — but the host must still
            // be public: a user-initiated start must never pivot into
            // localhost/LAN, and a privacy route is still enforced. Credential
            // grants are never attached to cleartext (see AccountGrant).
            for (int redirects = 0; redirects <= 5; redirects++) {
                String reason = NetworkTargetPolicy.publicHostReason(current,
                        NetworkGate.canConnect() && NetworkGate.resolvesTargetsLocally());
                if (reason != null) {
                    throw new IOException("Refused download URL: " + reason);
                }
                HttpURLConnection conn = NetworkGate.openHttp(current);
                this.connection = conn;
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                if (record.userAgent != null && !record.userAgent.isEmpty()) {
                    conn.setRequestProperty("User-Agent", record.userAgent);
                }
                // A Referer is only ever sent over HTTPS, or over cleartext when
                // the user explicitly approved that download. This keeps the
                // default path from leaking the source page over plain HTTP.
                boolean secureHop = current.regionMatches(true, 0, "https://", 0, 8);
                if (sendReferrer && (secureHop || record.allowInsecureReferer)
                        && record.referrer != null && !record.referrer.isEmpty()) {
                    conn.setRequestProperty("Referer", record.referrer);
                }
                try {
                    String cookie = com.mrnobody.browser.MrNobodyApp.accounts()
                            .headerForUrl(current);
                    if (cookie != null && !cookie.isEmpty()) {
                        conn.setRequestProperty("Cookie", cookie);
                    }
                } catch (Throwable ignored) {
                }
                // Keep Content-Length and written bytes in the same units.
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (from > 0) {
                    conn.setRequestProperty("Range", DownloadResume.rangeHeader(from));
                    if (record.etag != null) conn.setRequestProperty("If-Range", record.etag);
                }

                final int code;
                try {
                    code = conn.getResponseCode();
                } catch (IOException e) {
                    this.connection = null;
                    conn.disconnect();
                    throw e;
                }
                if (!isRedirect(code)) return new Connected(conn, current);
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                this.connection = null;
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Redirect had no Location header");
                }
                if (redirects == 5) throw new IOException("Too many redirects");
                String next = new URL(new URL(current), location).toString();
                if (!sameOrigin(current, next)) sendReferrer = false;
                current = next;
            }
            throw new IOException("Too many redirects");
        }

        private boolean isRedirect(int code) {
            return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
        }

        private boolean sameOrigin(String first, String second) {
            try {
                URL a = new URL(first);
                URL b = new URL(second);
                return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                        && a.getHost().equalsIgnoreCase(b.getHost())
                        && effectivePort(a) == effectivePort(b);
            } catch (Exception e) {
                return false;
            }
        }

        private int effectivePort(URL url) {
            int explicit = url.getPort();
            return explicit >= 0 ? explicit : url.getDefaultPort();
        }
    }

    private static long contentLength(HttpURLConnection conn) {
        try {
            long declared = conn.getHeaderFieldLong("Content-Length", -1);
            return declared;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Turn a stack trace into something worth showing a person. */
    private static String friendly(Throwable t) {
        if (t instanceof java.net.UnknownHostException) return "No connection";
        if (t instanceof java.net.SocketTimeoutException) return "The server stopped responding";
        if (t instanceof javax.net.ssl.SSLException) return "The secure connection failed";
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }
}
