package com.mrnobody.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.mrnobody.debug.ErrorLog;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Small Android Keystore-backed store for credential values.
 *
 * <p>The encrypted envelope lives in ordinary SharedPreferences; the AES key
 * is non-exportable and lives in Android Keystore. Existing plaintext values
 * are migrated on first read and removed if secure migration cannot complete.
 */
public final class EncryptedPreferences {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences prefs;
    private final String prefsName;
    private final String alias;
    private final SecureRandom random = new SecureRandom();

    public EncryptedPreferences(Context context, String prefsName, String alias) {
        Context app = context.getApplicationContext();
        this.prefsName = prefsName;
        this.alias = alias;
        this.prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    public synchronized String getString(String key, String fallback) {
        String raw = prefs.getString(key, null);
        if (raw == null) return fallback;

        // One-time migration from the former plaintext preference.
        if (!SecretCipher.isEncrypted(raw)) {
            if (putString(key, raw)) return raw;
            // Migration could not encrypt right now (Keystore not ready, lock
            // state, etc.). Return the existing plaintext so the app keeps
            // working and the next successful read retries the migration — a
            // transient failure must never destroy the value.
            ErrorLog.record("secret migration deferred for " + safeName(key));
            return raw;
        }

        SecretKey secretKey;
        try {
            secretKey = key(false);
        } catch (Exception e) {
            // The Keystore could not be loaded at all right now. That is a
            // transient condition, so the ciphertext is left in place — a load
            // failure must never silently erase a valid credential.
            ErrorLog.record("keystore temporarily unavailable for "
                    + safeName(key) + "; secret left in place");
            return fallback;
        }
        if (secretKey == null) {
            // The Keystore key is absent at the moment. That can be a transient
            // load failure rather than a corrupt secret, so the ciphertext is
            // left in place — a momentarily missing key must not silently erase
            // a valid credential. A later read retries once the key is back.
            ErrorLog.record("keystore key unavailable for "
                    + safeName(key) + "; secret left in place");
            return fallback;
        }
        try {
            return SecretCipher.decrypt(secretKey, scope(key), raw);
        } catch (Exception e) {
            // A concrete decrypt failure (malformed envelope, AEAD tag
            // mismatch) is permanent: the bytes will never decode, so remove
            // them so they cannot fail on every read or look configured.
            prefs.edit().remove(key).commit();
            ErrorLog.record("stored secret unusable for " + safeName(key));
            return fallback;
        }
    }

    public synchronized boolean putString(String key, String value) {
        if (value == null || value.isEmpty()) return remove(key);
        try {
            String encrypted = SecretCipher.encrypt(key(true), scope(key), value, random);
            return prefs.edit().putString(key, encrypted).commit();
        } catch (Exception e) {
            ErrorLog.record("could not encrypt " + safeName(key));
            return false;
        }
    }

    public synchronized boolean remove(String key) {
        return prefs.edit().remove(key).commit();
    }

    public synchronized boolean contains(String key) {
        // Presence only. A read here must never decrypt — and therefore never
        // hit the decrypt-failure path that removes a corrupt/transient value.
        String raw = prefs.getString(key, null);
        return raw != null && !raw.isEmpty();
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        java.security.Key existing = store.getKey(alias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) return null;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private String scope(String key) {
        return prefsName + ":" + key;
    }

    private static String safeName(String key) {
        if (key == null) return "credential";
        return key.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
