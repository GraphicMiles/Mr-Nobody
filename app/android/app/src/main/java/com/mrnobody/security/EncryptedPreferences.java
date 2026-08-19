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
            prefs.edit().remove(key).commit();
            ErrorLog.record("secret migration failed for " + safeName(key));
            return fallback;
        }

        try {
            SecretKey secretKey = key(false);
            if (secretKey == null) throw new IllegalStateException("Keystore key missing");
            return SecretCipher.decrypt(secretKey, scope(key), raw);
        } catch (Exception e) {
            // A corrupt or invalidated credential is unusable. Remove the
            // ciphertext so it cannot repeatedly fail or look configured.
            prefs.edit().remove(key).commit();
            ErrorLog.record("stored secret unavailable for " + safeName(key));
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
        return !getString(key, "").isEmpty();
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
