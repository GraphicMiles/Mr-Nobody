package com.mrnobody.identity;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

/**
 * The Android Keystore seam behind {@link DeviceIdentity}.
 *
 * <p>This is the one place the identity touches hardware. Everything else —
 * {@link DeviceIdentity} encoding, {@link SignedRequest} signing and
 * verification — is pure Java and testable on a JVM; this class is the thin
 * wrapper that cannot run off-device and is deliberately left as a single,
 * small surface.
 *
 * <p>The key is EC P-256, not Ed25519, for a reason recorded in the
 * architecture doc: Android Keystore's {@code KeyPairGenerator} cannot create
 * Curve 25519 through a public API, so choosing Ed25519 would force a software
 * key and destroy the one property the design exists for — the private key
 * never leaves the device. P-256 is the downgrade in fashion and the upgrade
 * in the property the user actually asked for.
 */
public final class AndroidKeyStoreIdentity {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    /** Stable alias. A new alias would orphan every existing installation's identity. */
    private static final String ALIAS = "mrnobody_device_identity_v1";

    private AndroidKeyStoreIdentity() {
    }

    /**
     * True when this install already has an identity. Does not create one.
     *
     * <p>The key is a stable install identifier. Mint it only when remote
     * execution is first used — never from diagnostics, first launch, or a
     * settings screen that only wants to report.
     */
    public static boolean exists() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            return ks.containsAlias(ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Load the installation identity, generating it if absent. Call only from
     * the remote-execution path. Idempotent: the same alias always yields the
     * same key.
     */
    public static DeviceIdentity loadOrCreate() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            generate();
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(ALIAS, null);
        if (entry == null) {
            throw new IllegalStateException("identity key " + ALIAS + " missing after generation");
        }
        PublicKey publicKey = entry.getCertificate().getPublicKey();
        return new DeviceIdentity(publicKey, entry.getPrivateKey(), securityLevel(ks));
    }

    private static void generate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
        kpg.initialize(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        kpg.generateKeyPair();
    }

    /**
     * Where the key actually lives, from {@code KeyInfo.getSecurityLevel()} —
     * reported, never assumed. A device that reports software backing still
     * works; the UI must simply not claim hardware.
     */
    private static String securityLevel(KeyStore ks) {
        try {
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(ALIAS, null);
            if (entry == null) return DeviceIdentity.LEVEL_UNKNOWN;
            PrivateKey key = entry.getPrivateKey();
            KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), ANDROID_KEYSTORE);
            KeyInfo info = factory.getKeySpec(key, KeyInfo.class);
            int level = info.getSecurityLevel();
            if (level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                    || level == KeyProperties.SECURITY_LEVEL_STRONGBOX) {
                return DeviceIdentity.LEVEL_HARDWARE;
            }
            if (level == KeyProperties.SECURITY_LEVEL_SOFTWARE) {
                return DeviceIdentity.LEVEL_SOFTWARE;
            }
            return DeviceIdentity.LEVEL_UNKNOWN;
        } catch (Exception e) {
            // Failing to read the level must not fail identity itself — it
            // only means we report "unknown" rather than pretend to know.
            return DeviceIdentity.LEVEL_UNKNOWN;
        }
    }
}
