package com.mrnobody.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class SecretCipherTest {

    private SecretKey key;

    @Before
    public void makeKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
    }

    @Test
    public void roundTripKeepsTheSecretOutOfTheEnvelope() throws Exception {
        String encrypted = SecretCipher.encrypt(key, "prefs:key", "super-secret", new SecureRandom());

        assertTrue(SecretCipher.isEncrypted(encrypted));
        assertFalse(encrypted.contains("super-secret"));
        assertEquals("super-secret", SecretCipher.decrypt(key, "prefs:key", encrypted));
    }

    @Test
    public void encryptionUsesAFreshIv() throws Exception {
        String a = SecretCipher.encrypt(key, "prefs:key", "same", new SecureRandom());
        String b = SecretCipher.encrypt(key, "prefs:key", "same", new SecureRandom());

        assertNotEquals(a, b);
    }

    @Test
    public void anEnvelopeCannotBeMovedToAnotherPreference() throws Exception {
        String encrypted = SecretCipher.encrypt(key, "prefs:key-a", "secret", new SecureRandom());

        try {
            SecretCipher.decrypt(key, "prefs:key-b", encrypted);
            fail("different associated data must fail authentication");
        } catch (GeneralSecurityException expected) {
            // expected
        }
    }

    @Test
    public void malformedCiphertextFailsClosed() throws Exception {
        try {
            SecretCipher.decrypt(key, "prefs:key", "v1:not-base64:not-base64");
            fail("malformed data must not decrypt");
        } catch (GeneralSecurityException expected) {
            // expected
        }
    }
}
