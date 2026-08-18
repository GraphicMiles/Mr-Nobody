package com.mrnobody.agent.policy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies a terminal command (and, in V2, tool requests) as ALLOW, CONFIRM
 * or DENY. The agent/LLM output is data, not authority — nothing executes
 * without clearing this gate.
 */
public final class PolicyGate {

    public enum Decision { ALLOW, CONFIRM, DENY }

    // V1: safe read-only utilities are allowed; destructive/system ops are
    // denied or require confirmation. Matching is by the command's first word
    // (the binary), so bare "ls" and "ls -la" are both allowed.
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "sha256", "md5", "hash", "list", "ls", "cat", "head", "stat", "identify"));

    private static final String[] DENIED = {
            "rm -rf", "dd ", "mkfs", "su ", "sudo ", "chmod", "chown", "mount", "reboot",
            "shutdown", "adb ", "getprop", "setprop", "pm ", "am ", "/system", "/data/data"
    };

    /**
     * Toolchains Android does not ship. Matching is by whole word so "repo"
     * does not fire on "report". These run on the remote worker, not here.
     */
    private static final Set<String> REMOTE = new HashSet<>(Arrays.asList(
            "git", "python", "python3", "pip", "node", "npm", "npx", "yarn",
            "curl", "wget", "ssh", "rsync", "go", "rustc", "cargo", "javac",
            "gcc", "make", "docker", "kubectl", "clone", "repo", "repository",
            "commit", "checkout", "branch", "push", "pull"));

    public Decision classify(String command) {
        if (command == null || command.trim().isEmpty()) return Decision.DENY;
        String c = command.trim();
        String lower = c.toLowerCase(Locale.ROOT);

        for (String d : DENIED) {
            if (lower.contains(d)) return Decision.DENY;
        }
        String binary = firstWord(c);
        if (ALLOWED.contains(binary)) return Decision.ALLOW;
        // Unknown / potentially side-effecting commands require the user.
        return Decision.CONFIRM;
    }

    /**
     * True when the command needs a toolchain that only the remote worker has.
     * Checked before any execution attempt so the failure is honest ("git needs
     * the server") rather than a baffling "command not found".
     */
    public boolean requiresRemote(String command) {
        if (command == null) return false;
        for (String word : command.trim().toLowerCase(Locale.ROOT).split("[\\s/]+")) {
            if (REMOTE.contains(word)) return true;
        }
        return false;
    }

    private static String firstWord(String c) {
        int sp = c.indexOf(' ');
        return (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
    }
}
