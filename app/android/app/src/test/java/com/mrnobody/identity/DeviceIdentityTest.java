package com.mrnobody.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * The pure half of the identity: encoding, fingerprinting and signing, against
 * a JVM-generated EC P-256 key. The Android Keystore seam cannot run here (it
 * needs hardware), which is exactly why these pieces are kept separate from
 * it.
 */
public class DeviceIdentityTest {

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    @Test
    public void identityIsTheEncodedPublicKeyAndIsStable() throws Exception {
        KeyPair kp = keyPair();
        DeviceIdentity id = new DeviceIdentity(kp.getPublic(), kp.getPrivate(), "software");

        assertEquals(id.identity(), id.identity());
        assertTrue(id.identity().length() > 20);
    }

    @Test
    public void fingerprintIsStableHexOfTheIdentity() throws Exception {
        KeyPair kp = keyPair();
        DeviceIdentity id = new DeviceIdentity(kp.getPublic(), kp.getPrivate(), "software");

        String fp = id.fingerprint();
        assertEquals(64, fp.length());
        assertEquals(fp, id.fingerprint());
        assertTrue(fp.matches("[0-9a-f]+"));
    }

    @Test
    public void distinctKeysHaveDistinctIdentities() throws Exception {
        DeviceIdentity a = new DeviceIdentity(keyPair().getPublic(), keyPair().getPrivate(), "software");
        DeviceIdentity b = new DeviceIdentity(keyPair().getPublic(), keyPair().getPrivate(), "software");

        assertNotEquals(a.identity(), b.identity());
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    public void signProducesAVerifiableSignatureOverTheExactBytes() throws Exception {
        KeyPair kp = keyPair();
        DeviceIdentity id = new DeviceIdentity(kp.getPublic(), kp.getPrivate(), "software");

        byte[] data = "run-task:42".getBytes();
        byte[] sig = id.sign(data);

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(kp.getPublic());
        verifier.update(data);
        assertTrue(verifier.verify(sig));
    }

    @Test
    public void securityLevelIsReportedAsGiven() throws Exception {
        KeyPair kp = keyPair();
        assertEquals("hardware",
                new DeviceIdentity(kp.getPublic(), kp.getPrivate(), "hardware").securityLevel());
        // An empty or null level degrades to "unknown", never to a lie.
        assertEquals("unknown",
                new DeviceIdentity(kp.getPublic(), kp.getPrivate(), null).securityLevel());
    }
}
