package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.policy.PolicyGate;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * A restricted local terminal. Not an unrestricted shell: every command is
 * classified by the {@link PolicyGate} as ALLOW / CONFIRM / DENY. V1 implements
 * a tiny safe subset (hash a file, list the app workspace); V2 expands it.
 */
public final class TerminalTool implements Tool {

    private final PolicyGate policy;

    public TerminalTool(PolicyGate policy) {
        this.policy = policy;
    }

    @Override
    public String name() {
        return "terminal";
    }

    @Override
    public String description() {
        return "Run a small set of approved local utilities (hash, inspect).";
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String command = request.param("cmd");
        if (command == null || command.isEmpty()) return ToolResult.fail("terminal needs 'cmd'");

        PolicyGate.Decision decision = policy.classify(command);
        if (decision == PolicyGate.Decision.DENY) {
            return ToolResult.fail("command denied by policy: " + command);
        }
        if (decision == PolicyGate.Decision.CONFIRM) {
            // V1: confirmation requires the user; surface it instead of auto-running.
            return ToolResult.fail("command needs confirmation: " + command);
        }

        // Only ALLOW commands reach here. V1 supports a tiny, safe subset.
        if (command.startsWith("sha256 ")) {
            return sha256(command.substring("sha256 ".length()).trim(), context);
        }
        return ToolResult.fail("unknown command: " + command);
    }

    private ToolResult sha256(String path, Context context) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return ToolResult.fail("file not found: " + path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return ToolResult.ok("sha256(" + f.getName() + ") = " + sb);
        } catch (Exception e) {
            return ToolResult.fail("hash failed: " + e.getMessage());
        }
    }
}
