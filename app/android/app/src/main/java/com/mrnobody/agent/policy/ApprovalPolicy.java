package com.mrnobody.agent.policy;

import com.mrnobody.agent.core.ApprovalDecision;
import com.mrnobody.agent.core.ImpactKind;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolPipeline;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a call's permission from three inputs, in a fixed order.
 *
 * <pre>
 *   per-tool override   (most specific — what the user said about THIS tool)
 *        ↓ falls through when absent
 *   approval mode       (what the user said in general)
 *        ↓ falls through when the mode is silent
 *   tier default        (what the capability itself implies)
 * </pre>
 *
 * <p>Replaces {@link ToolPipeline.TierApproval}, which only looked at the tier
 * and therefore sent every EXEC call to a confirmer — a confirmer that, until
 * now, was never attached, so the whole CONFIRM tier resolved to a refusal.
 *
 * <p><b>An override can only ever be more restrictive than the mode is not
 * true here, and that is deliberate.</b> ALWAYS_ALLOW genuinely relaxes the
 * mode for one tool, because "stop asking me about downloads" is the request
 * that makes an approval prompt bearable. What an override cannot do is
 * survive a {@code Guard}: guards run after this and can only subtract. So the
 * blast radius of a mistaken override is one tool, never the policy.
 */
public final class ApprovalPolicy implements ToolPipeline.Approval {

    /** What the user has said about one specific tool. */
    public enum Rule {
        /** No opinion recorded — the mode decides. */
        ASK_EACH_TIME,
        /** Run this tool without prompting. */
        ALWAYS_ALLOW,
        /** Never run this tool, whatever the mode says. */
        NEVER
    }

    /** Where the overrides live, so the policy does not own persistence. */
    public interface Overrides {
        Rule forTool(String tool);

        Overrides NONE = tool -> Rule.ASK_EACH_TIME;
    }

    private volatile ApprovalMode mode;
    private volatile Overrides overrides;

    public ApprovalPolicy(ApprovalMode mode, Overrides overrides) {
        this.mode = mode == null ? ApprovalMode.CAUTIOUS : mode;
        this.overrides = overrides == null ? Overrides.NONE : overrides;
    }

    /**
     * Bind where per-tool overrides are read from.
     *
     * <p>Needed because the store outlives any one decision: "always allow"
     * is recorded by the confirmation prompt, and unless the policy reads the
     * same instance the choice is written down and never consulted -- the user
     * would tick the box and keep being asked.
     */
    public void setOverrides(Overrides next) {
        this.overrides = next == null ? Overrides.NONE : next;
    }

    public ApprovalMode mode() {
        return mode;
    }

    public void setMode(ApprovalMode next) {
        this.mode = next == null ? ApprovalMode.CAUTIOUS : next;
    }

    @Override
    public ApprovalDecision decide(ToolCall call) {
        if (call == null) {
            return ApprovalDecision.deny(ApprovalDecision.Source.DEFAULT, "no call to judge");
        }

        ImpactKind impact = ImpactKind.of(call.tool(), call.action(),
                call.params() == null ? "" : String.valueOf(call.params()));
        if (impact.alwaysConfirm()) {
            String reason = impact == ImpactKind.DELETE
                    ? "deletes content"
                    : impact == ImpactKind.FINALIZE
                            ? "finalizes or exports a reviewed design"
                            : "spends money or checks out";
            return ApprovalDecision.confirm(ApprovalDecision.Source.TIER, reason);
        }

        Rule rule = overrides.forTool(call.tool());
        if (rule == Rule.NEVER) {
            return ApprovalDecision.deny(ApprovalDecision.Source.USER_OVERRIDE,
                    "you turned " + call.tool() + " off");
        }
        if (rule == Rule.ALWAYS_ALLOW) {
            return ApprovalDecision.allow(ApprovalDecision.Source.USER_OVERRIDE,
                    "you allowed " + call.tool() + " without asking");
        }

        if (mode.requiresConfirmation(call.tier())) {
            return ApprovalDecision.confirm(ApprovalDecision.Source.MODE, describe(call));
        }

        return ApprovalDecision.allow(ApprovalDecision.Source.TIER, "");
    }

    /**
     * Why this call is being shown to the user.
     *
     * <p>Phrased as the consequence, not the tier name. "EXEC" means nothing
     * to the person holding the phone; "runs a command on your device" is the
     * thing they are actually agreeing to.
     */
    private static String describe(ToolCall call) {
        switch (call.tier()) {
            case EXEC:
                return "runs a command or acts outside this device";
            case WRITE:
                return "acts on a live page (click, type, or submit)";
            case SANDBOX:
                return "writes a file into this app's sandbox";
            case READ:
            default:
                return "reads data";
        }
    }

    /**
     * Process-session overrides. Concurrent because approval is written from
     * the UI and read by WorkManager/tool threads.
     */
    public static final class MapOverrides implements Overrides {
        private final Map<String, Rule> byTool = new ConcurrentHashMap<>();

        @Override
        public Rule forTool(String tool) {
            if (tool == null) return Rule.ASK_EACH_TIME;
            Rule o = byTool.get(tool.toLowerCase(Locale.ROOT));
            return o == null ? Rule.ASK_EACH_TIME : o;
        }

        public MapOverrides set(String tool, Rule value) {
            if (tool == null) return this;
            String key = tool.toLowerCase(Locale.ROOT);
            if (value == null || value == Rule.ASK_EACH_TIME) {
                byTool.remove(key);
            } else {
                byTool.put(key, value);
            }
            return this;
        }

        public Map<String, Rule> all() {
            return Collections.unmodifiableMap(new HashMap<>(byTool));
        }
    }
}
