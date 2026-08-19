package com.mrnobody.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Pure AES-GCM envelope used by the Android Keystore-backed preference seam. */
public final class SecretCipher {

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SecretCipher() {
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(VERSION + ":");
    }

    public static String encrypt(SecretKey key, String scope, String plaintext,
                                 SecureRandom random) throws GeneralSecurityException {
        if (key == null) throw new GeneralSecurityException("missing secret key");
        if (plaintext == null) plaintext = "";
        if (random == null) random = new SecureRandom();

        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad(scope));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return VERSION + ":"
                + Base64.getEncoder().encodeToString(iv) + ":"
                + Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decrypt(SecretKey key, String scope, String envelope)
            throws GeneralSecurityException {
        if (key == null) throw new GeneralSecurityException("missing secret key");
        if (!isEncrypted(envelope)) throw new GeneralSecurityException("unknown secret envelope");

        String[] parts = envelope.split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new GeneralSecurityException("malformed secret envelope");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            if (iv.length != IV_BYTES) throw new GeneralSecurityException("bad secret IV");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(scope));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("malformed secret encoding", e);
        }
    }

    private static byte[] aad(String scope) {
        return (scope == null ? "" : scope).getBytes(StandardCharsets.UTF_8);
    }
}
