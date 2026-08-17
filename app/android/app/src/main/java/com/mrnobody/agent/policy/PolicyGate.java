package com.mrnobody.agent.policy;

import java.util.Locale;

/**
 * Classifies a terminal command (and, in V2, tool requests) as ALLOW, CONFIRM
 * or DENY. The agent/LLM output is data, not authority — nothing executes
 * without clearing this gate.
 */
public final class PolicyGate {

    public enum Decision { ALLOW, CONFIRM, DENY }

    // V1: safe read-only utilities are allowed; destructive/system ops are
    // denied or require confirmation.
    private static final String[] ALLOWED_PREFIXES = {
            "sha256 ", "md5 ", "hash ", "list ", "ls ", "cat ", "head ", "stat ", "identify "
    };

    private static final String[] DENIED = {
            "rm -rf", "dd ", "mkfs", "su ", "sudo ", "chmod", "chown", "mount", "reboot",
            "shutdown", "adb ", "getprop", "setprop", "pm ", "am ", "/system", "/data/data"
    };

    public Decision classify(String command) {
        if (command == null || command.trim().isEmpty()) return Decision.DENY;
        String c = command.trim();
        String lower = c.toLowerCase(Locale.ROOT);

        for (String d : DENIED) {
            if (lower.contains(d)) return Decision.DENY;
        }
        for (String p : ALLOWED_PREFIXES) {
            if (lower.startsWith(p)) return Decision.ALLOW;
        }
        // Unknown / potentially side-effecting commands require the user.
        return Decision.CONFIRM;
    }
}
