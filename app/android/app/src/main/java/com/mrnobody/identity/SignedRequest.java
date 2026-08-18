package com.mrnobody.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;

/**
 * A signed request from a device to the remote worker: the client half of
 * server integration.
 *
 * <p>The server verifies three things, in order, and this class is what makes
 * each one possible from the client side:
 *
 * <ol>
 *   <li><b>Freshness</b> — the timestamp must fall inside the replay window,
 *       so a captured request cannot be replayed later.</li>
 *   <li><b>Integrity</b> — the signature covers the whole canonical payload
 *       (nonce, timestamp, body), so nothing can be altered in transit.</li>
 *   <li><b>Possession</b> — the signature is over the device key, so it is
 *       proof the request came from the installation that owns the public key
 *       on file.</li>
 * </ol>
 *
 * <p>Pure Java on purpose: the whole envelope can be built and verified on a
 * JVM with no server in existence. {@code verify} is the server's half of the
 * contract, kept here so the two halves can never drift apart.
 */
public final class SignedRequest {

    /** Default replay window: a request is valid for 5 minutes around its timestamp. */
    public static final long DEFAULT_WINDOW_MS = 5 * 60_000L;

    /** A fresh, unpredictable nonce, so identical requests sign differently. */
    public static final int NONCE_BYTES = 16;

    private final String nonce;
    private final long timestamp;
    private final String payload;
    private final byte[] signature;

    public SignedRequest(String nonce, long timestamp, String payload, byte[] signature) {
        this.nonce = nonce;
        this.timestamp = timestamp;
        this.payload = payload;
        this.signature = signature;
    }

    public String nonce() {
        return nonce;
    }

    public long timestamp() {
        return timestamp;
    }

    public String payload() {
        return payload;
    }

    public byte[] signature() {
        return signature;
    }

    /**
     * The exact byte sequence that is signed. Three newline-separated fields,
     * so a nonce can never be confused with a timestamp or a payload, and a
     * payload containing the delimiter cannot re-map the fields (the signature
     * still covers the whole string, but the fields are unambiguous).
     */
    public String canonical() {
        return nonce + '\n' + timestamp + '\n' + payload;
    }

    /**
     * Sign a request as this device, timestamped now.
     *
     * @throws GeneralSecurityException if the device key cannot sign.
     */
    public static SignedRequest sign(DeviceIdentity identity, String nonce, String payload)
            throws GeneralSecurityException {
        long timestamp = System.currentTimeMillis();
        byte[] signature = identity.sign(
                (nonce + '\n' + timestamp + '\n' + payload).getBytes(StandardCharsets.UTF_8));
        return new SignedRequest(nonce, timestamp, payload, signature);
    }

    /**
     * Verify freshness, integrity and possession against {@code key}. Returns
     * false rather than throwing on a malformed signature — a bad request is a
     * rejected request, not a server error.
     */
    public boolean verify(PublicKey key, long nowMillis, long windowMillis) {
        if (Math.abs(nowMillis - timestamp) > windowMillis) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(canonical().getBytes(StandardCharsets.UTF_8));
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** {@link #verify(PublicKey, long, long)} with the default replay window. */
    public boolean verify(PublicKey key, long nowMillis) {
        return verify(key, nowMillis, DEFAULT_WINDOW_MS);
    }
}
