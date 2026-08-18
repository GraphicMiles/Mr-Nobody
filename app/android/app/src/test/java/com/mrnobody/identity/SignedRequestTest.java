package com.mrnobody.identity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

/**
 * The signed-request envelope, exercised as the server would: sign on the
 * device, then verify freshness, integrity and possession against the public
 * key on file. The server half lives in {@link SignedRequest#verify} so the
 * two sides of the contract can never drift.
 */
public class SignedRequestTest {

    private static DeviceIdentity device() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        return new DeviceIdentity(kp.getPublic(), kp.getPrivate(), "software");
    }

    @Test
    public void aSignedRequestVerifiesAgainstItsOwnKey() throws Exception {
        DeviceIdentity id = device();
        SignedRequest req = SignedRequest.sign(id, "nonce-1", "run-task:7");

        assertTrue(req.verify(id.publicKey(), req.timestamp()));
    }

    @Test
    public void tamperingWithThePayloadBreaksTheSignature() throws Exception {
        DeviceIdentity id = device();
        SignedRequest req = SignedRequest.sign(id, "nonce-1", "spend:5");
        SignedRequest altered = new SignedRequest(req.nonce(), req.timestamp(), "spend:50", req.signature());

        assertFalse(altered.verify(id.publicKey(), altered.timestamp()));
    }

    @Test
    public void tamperingWithTheNonceBreaksTheSignature() throws Exception {
        DeviceIdentity id = device();
        SignedRequest req = SignedRequest.sign(id, "nonce-1", "spend:5");
        SignedRequest altered = new SignedRequest("nonce-2", req.timestamp(), req.payload(), req.signature());

        assertFalse(altered.verify(id.publicKey(), altered.timestamp()));
    }

    @Test
    public void theWrongKeyCannotVerify() throws Exception {
        DeviceIdentity a = device();
        DeviceIdentity b = device();
        SignedRequest req = SignedRequest.sign(a, "nonce-1", "run-task:7");

        assertFalse(req.verify(b.publicKey(), req.timestamp()));
    }

    @Test
    public void aRequestOutsideTheReplayWindowIsRejected() throws Exception {
        DeviceIdentity id = device();
        SignedRequest req = SignedRequest.sign(id, "nonce-1", "run-task:7");

        assertFalse("too old", req.verify(id.publicKey(), req.timestamp() + SignedRequest.DEFAULT_WINDOW_MS + 1));
        assertFalse("too new", req.verify(id.publicKey(), req.timestamp() - SignedRequest.DEFAULT_WINDOW_MS - 1));
        // Inside the window, both sides of the clock skew are accepted.
        assertTrue(req.verify(id.publicKey(), req.timestamp() + 1000));
        assertTrue(req.verify(id.publicKey(), req.timestamp() - 1000));
    }

    @Test
    public void identicalRequestsStillDifferBecauseOfTheNonce() throws Exception {
        DeviceIdentity id = device();
        SignedRequest one = SignedRequest.sign(id, "nonce-1", "run-task:7");
        SignedRequest two = SignedRequest.sign(id, "nonce-2", "run-task:7");

        assertFalse(java.util.Arrays.equals(one.signature(), two.signature()));
    }
}
