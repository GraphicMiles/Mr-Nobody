package com.mrnobody.agent.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class NetworkTargetPolicyTest {

    @Test
    public void publicLiteralIsAllowed() {
        assertNull(NetworkTargetPolicy.publicReason("https://8.8.8.8/dns", false));
        assertNull(NetworkTargetPolicy.publicReason("https://[2606:4700:4700::1111]/", false));
    }

    @Test
    public void autonomousTargetsRequireHttps() {
        assertNotNull(NetworkTargetPolicy.publicReason("http://example.com/", false));
    }

    @Test
    public void localAndReservedLiteralsAreRefused() {
        for (String url : new String[]{
                "http://127.0.0.1/", "http://10.0.0.1/", "http://169.254.169.254/",
                "http://192.168.1.1/", "http://100.64.0.1/", "http://[::1]/",
                "http://[fc00::1]/", "http://198.18.0.1/"}) {
            assertNotNull(url, NetworkTargetPolicy.publicReason(url, false));
        }
    }

    @Test
    public void localNamesAndObscuredNumbersAreRefused() {
        assertNotNull(NetworkTargetPolicy.publicReason("http://localhost:8080/", false));
        assertNotNull(NetworkTargetPolicy.publicReason("http://localhost./", false));
        assertNotNull(NetworkTargetPolicy.publicReason("http://router.local/", false));
        assertNotNull(NetworkTargetPolicy.publicReason("http://router.home.arpa/", false));
        assertNotNull(NetworkTargetPolicy.publicReason("http://router/", false));
        assertNotNull(NetworkTargetPolicy.publicReason("http://2130706433/", false));
        assertNotNull(NetworkTargetPolicy.publicReason("http://0x7f000001/", false));
        assertNotNull(NetworkTargetPolicy.publicReason("http://[fe80::1%25wlan0]/", false));
    }

    @Test
    public void anyPrivateDnsAnswerRefusesTheHost() throws Exception {
        NetworkTargetPolicy.Resolver mixed = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("192.168.0.10")
        };
        assertNotNull(NetworkTargetPolicy.publicReason("https://example.test/", true, mixed));
    }

    @Test
    public void proxiedValidationDoesNotLeakThroughLocalDns() {
        AtomicBoolean called = new AtomicBoolean(false);
        NetworkTargetPolicy.Resolver resolver = host -> {
            called.set(true);
            throw new AssertionError("must not resolve locally");
        };
        assertNull(NetworkTargetPolicy.publicReason("https://example.com/", false, resolver));
        org.junit.Assert.assertFalse(called.get());
    }

    @Test
    public void urlCredentialsAreRefused() {
        assertNotNull(NetworkTargetPolicy.publicReason("https://user:pass@example.com/", false));
    }

    @Test
    public void publicHostReasonAllowsUserChosenPublicHttpButBlocksLocal() {
        // A user-chosen public http:// site is allowed for the visible-browser
        // download engine (which does not force HTTPS), but local/private/LAN
        // hosts and obscured numerics are still refused.
        assertNull(NetworkTargetPolicy.publicHostReason("http://example.com/file.mp4", false));
        assertNull(NetworkTargetPolicy.publicHostReason("https://8.8.8.8/dns", false));
        for (String url : new String[]{
                "http://127.0.0.1/", "http://10.0.0.1/", "http://169.254.169.254/",
                "http://192.168.1.1/", "http://[::1]/", "http://localhost:8080/",
                "http://router.local/", "http://2130706433/", "http://0x7f000001/"}) {
            assertNotNull(url, NetworkTargetPolicy.publicHostReason(url, false));
        }
        assertNotNull(NetworkTargetPolicy.publicHostReason("http://user:pass@example.com/", false));
        assertNotNull(NetworkTargetPolicy.publicHostReason("ftp://example.com/", false));
    }
}
