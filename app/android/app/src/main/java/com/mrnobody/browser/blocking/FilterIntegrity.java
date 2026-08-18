package com.mrnobody.browser.blocking;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Checks that a filter list is the one we shipped.
 *
 * <p>The blocklist decides what is blocked, so an attacker who can change it
 * can silently unblock their own trackers. Today the list is bundled in the
 * APK and therefore signed by the APK signature — which is real protection,
 * and the reason this is a V3 item rather than an emergency. It stops being
 * sufficient the moment a list can be updated over the network, which is what
 * decentralised distribution means.
 *
 * <p>So this exists before that does, deliberately: the integrity check has to
 * be in place and exercised <em>before</em> anything is allowed to fetch a
 * list, not bolted on afterwards.
 *
 * <p><b>Digest, not signature, at this stage.</b> A SHA-256 pinned at build
 * time detects corruption and substitution of a bundled asset. It does not
 * establish authorship, so it cannot on its own authorise a downloaded list —
 * that needs a public key, which needs key management and rotation, which is
 * the actual work of remote updates. Claiming otherwise would be the kind of
 * half-measure that reads as security and is not.
 *
 * <p>Rollback protection is by version: a list may replace one of the same or
 * older version only if its digest also matches, so an attacker cannot serve
 * a genuine but outdated list to reopen closed holes.
 */
public final class FilterIntegrity {

    /** Outcome of a check, with enough detail to explain a refusal. */
    public static final class Result {
        public final boolean ok;
        public final String reason;
        public final String actualDigest;

        Result(boolean ok, String reason, String actualDigest) {
            this.ok = ok;
            this.reason = reason;
            this.actualDigest = actualDigest;
        }

        public static Result pass(String digest) {
            return new Result(true, null, digest);
        }

        public static Result fail(String reason, String digest) {
            return new Result(false, reason, digest);
        }
    }

    private FilterIntegrity() {
    }

    /** Lowercase hex SHA-256 of {@code bytes}. */
    public static String digest(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(bytes == null ? new byte[0] : bytes);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is mandatory on every Android device; if it is missing
            // something is very wrong and no digest is the honest answer.
            return "";
        }
    }

    /** Read a stream fully, bounded so a hostile source cannot exhaust memory. */
    public static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("filter list exceeds " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Verify content against an expected digest.
     *
     * <p>An empty expectation is treated as a failure rather than a pass. A
     * check that silently succeeds when it was never configured is worse than
     * no check, because it reports safety it never established — the same
     * failure mode as the privacy audit that scanned a dead tree.
     */
    public static Result verify(byte[] content, String expectedDigest) {
        String actual = digest(content);
        if (expectedDigest == null || expectedDigest.trim().isEmpty()) {
            return Result.fail("no expected digest was configured", actual);
        }
        String expected = expectedDigest.trim().toLowerCase(Locale.ROOT);
        if (!expected.equals(actual)) {
            return Result.fail("digest mismatch: expected " + shorten(expected)
                    + ", got " + shorten(actual), actual);
        }
        return Result.pass(actual);
    }

    /**
     * Whether a candidate list may replace the installed one.
     *
     * <p>Refuses a lower version outright. Refuses an equal version unless the
     * digest matches, which is what stops an attacker replaying a genuine
     * older list to reopen holes that a later list closed.
     */
    public static Result canReplace(int installedVersion, int candidateVersion,
                                    byte[] candidate, String expectedDigest) {
        if (candidateVersion < installedVersion) {
            return Result.fail("refusing to roll back from version " + installedVersion
                    + " to " + candidateVersion, digest(candidate));
        }
        Result verified = verify(candidate, expectedDigest);
        if (!verified.ok) return verified;
        if (candidateVersion == installedVersion) {
            // Same version, verified digest: a reinstall of what we already
            // have. Harmless, but say so rather than implying an upgrade.
            return Result.pass(verified.actualDigest);
        }
        return Result.pass(verified.actualDigest);
    }

    private static String shorten(String digest) {
        if (digest == null || digest.length() <= 12) return String.valueOf(digest);
        return digest.substring(0, 12) + "…";
    }
}
