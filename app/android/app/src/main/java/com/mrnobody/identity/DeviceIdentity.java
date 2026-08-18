package com.mrnobody.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * The anonymous identity of one installation.
 *
 * <p>One installation = one identity. It asserts exactly one thing — <em>this
 * installation owns these tasks</em> — and nothing else. No account, no email,
 * no phone, no cross-installation linkage. The public key <em>is</em> the
 * identity; the private key never leaves the device.
 *
 * <p>On device the keypair comes from Android Keystore (see
 * {@link AndroidKeyStoreIdentity}) and the private key is non-exportable. The
 * {@code securityLevel} field records where the key actually lives, so a
 * software-backed key is reported as such and never dressed up as hardware.
 * The {@code "hardware"} / {@code "software"} / {@code "unknown"} vocabulary
 * is the honest ceiling: {@code KeyInfo.getSecurityLevel()} is the only way to
 * know, and it must be reported, not assumed.
 */
public final class DeviceIdentity {

    public static final String LEVEL_HARDWARE = "hardware";
    public static final String LEVEL_SOFTWARE = "software";
    public static final String LEVEL_UNKNOWN = "unknown";

    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final String securityLevel;

    public DeviceIdentity(PublicKey publicKey, PrivateKey privateKey, String securityLevel) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.securityLevel = securityLevel == null || securityLevel.isEmpty()
                ? LEVEL_UNKNOWN : securityLevel;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /**
     * The stable identity string: the public key, base64-encoded (X.509
     * SubjectPublicKeyInfo). Deterministic for a given key, opaque to anyone
     * who does not already have the key.
     */
    public String identity() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * A shorter derived identifier for logs and displays: SHA-256 of
     * {@link #identity()}, lower-case hex. Not a secret and not the identity
     * itself — it is a compact, human-scannable handle for the same key.
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(identity().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            // SHA-256 is always present; this cannot happen on a real runtime.
            throw new IllegalStateException(e);
        }
    }

    /** Where the private key lives: {@code hardware}, {@code software}, or {@code unknown}. */
    public String securityLevel() {
        return securityLevel;
    }

    /**
     * Sign bytes with the device key (ECDSA over SHA-256). On device this is
     * the Keystore-held, non-exportable key; the signature is proof of
     * possession, never the key itself.
     */
    public byte[] sign(byte[] data) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(privateKey);
        s.update(data);
        return s.sign();
    }
}
