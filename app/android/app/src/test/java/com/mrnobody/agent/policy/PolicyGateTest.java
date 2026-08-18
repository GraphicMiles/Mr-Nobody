package com.mrnobody.agent.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The terminal's policy gate: what is allowed on-device, what is always
 * refused, and what needs the remote worker's toolchain.
 */
public class PolicyGateTest {

    private final PolicyGate gate = new PolicyGate();

    @Test
    public void safeReadCommandsAreAllowed() {
        assertEquals(PolicyGate.Decision.ALLOW, gate.classify("sha256 report.pdf"));
        assertEquals(PolicyGate.Decision.ALLOW, gate.classify("ls"));
        assertEquals(PolicyGate.Decision.ALLOW, gate.classify("cat note.txt"));
    }

    @Test
    public void destructiveCommandsAreDenied() {
        assertEquals(PolicyGate.Decision.DENY, gate.classify("rm -rf /"));
        assertEquals(PolicyGate.Decision.DENY, gate.classify("sudo reboot"));
        assertEquals(PolicyGate.Decision.DENY, gate.classify("chmod 777 /"));
        assertEquals(PolicyGate.Decision.DENY, gate.classify("dd if=/dev/zero of=/dev/sda"));
    }

    @Test
    public void toolchainCommandsAreFlaggedRemote() {
        assertTrue(gate.requiresRemote("git clone https://github.com/x/y"));
        assertTrue(gate.requiresRemote("pull my repo"));
        assertTrue(gate.requiresRemote("python script.py"));
        assertTrue(gate.requiresRemote("pip install requests"));
        assertTrue(gate.requiresRemote("npm install"));
        assertTrue(gate.requiresRemote("curl https://example.com"));
        assertFalse(gate.requiresRemote("sha256 report.pdf"));
        assertFalse(gate.requiresRemote("ls -la"));
    }

    @Test
    public void anEmptyCommandIsDenied() {
        assertEquals(PolicyGate.Decision.DENY, gate.classify(null));
        assertEquals(PolicyGate.Decision.DENY, gate.classify(""));
        assertEquals(PolicyGate.Decision.DENY, gate.classify("   "));
    }
}
