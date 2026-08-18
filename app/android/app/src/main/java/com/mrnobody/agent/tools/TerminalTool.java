package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.policy.PolicyGate;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A restricted local terminal. Not an unrestricted shell: every command is
 * classified by the {@link PolicyGate}, destructive commands are refused, and
 * only the on-device subset actually runs — hashing, listing and reading files
 * inside the app workspace, via {@link TerminalRuntime}.
 *
 * <p>Commands that need a toolchain the device does not ship (git, python,
 * node) are reported honestly: they run on the remote worker, not here. That
 * is the genuine limitation — Android has no such binaries, and bundling one
 * is an explicit non-goal — not a gap the on-device tool can close.
 *
 * <p>Approval is the pipeline's job (EXEC tier reaches the confirmation
 * prompt); this tool's internal gate is the final <em>safety</em> line, not a
 * second approval. DENY always refuses; everything else is safe to attempt.
 */
public final class TerminalTool implements Tool {

    private final PolicyGate policy;

    public TerminalTool(PolicyGate policy) {
        this.policy = policy;
    }

    private static final ToolSpec SPEC = ToolSpec.named("terminal")
            .describedAs("Run approved local utilities (hash, list, read) in the workspace.")
            // The only EXEC tool in the app: it runs commands, so it asks.
            .tier(Tier.EXEC)
            .param(ParamSpec.string("cmd", true, "The command to run, e.g. \"sha256 <path>\"."))
            .returns(OutputSpec.of(
                    value -> String.valueOf(value.get("output")), "command", "output"))
            .timeout(15_000)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String command = request.param("cmd");
        if (command == null || command.isEmpty()) return ToolResult.fail("terminal needs 'cmd'");

        // Safety line, always on: destructive commands are refused no matter
        // what the approval flow decided.
        if (policy.classify(command) == PolicyGate.Decision.DENY) {
            return ToolResult.fail("command denied by policy: " + command);
        }

        // A toolchain the device does not ship. Honest, not a cryptic failure.
        if (policy.requiresRemote(command)) {
            return ToolResult.fail("This needs the remote worker — " + command
                    + " uses a toolchain (git/python/node) that Android does not ship, "
                    + "so it runs on Mr Nobody's servers, not on this device.");
        }

        File workspace = new File(context.getFilesDir(), "workspace");
        //noinspection ResultOfMethodCallIgnored
        workspace.mkdirs();
        TerminalRuntime.Result r = TerminalRuntime.run(workspace, command);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("command", command);
        value.put("output", r.output);
        return r.ok ? ToolResult.ok(value) : ToolResult.fail(r.output);
    }
}
