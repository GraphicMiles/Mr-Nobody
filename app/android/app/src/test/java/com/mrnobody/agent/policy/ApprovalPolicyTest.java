package com.mrnobody.agent.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.ApprovalDecision;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolRequest;

import org.junit.Test;

/**
 * Permission resolution: override, then mode, then tier.
 *
 * <p>Replaces the pipeline's original {@code TierApproval}, which judged on
 * tier alone and therefore sent every EXEC call to a confirmer — a confirmer
 * that was never attached, so the entire CONFIRM tier resolved to a refusal
 * and the terminal could run exactly one command.
 */
public class ApprovalPolicyTest {

    private static ToolCall call(String tool, Tier tier) {
        return ToolCall.of(tool, ToolRequest.of(tool), tier);
    }

    // ----------------------------------------------------------- tier × mode

    @Test
    public void cautiousAsksBeforeAnythingThatIsNotJustLooking() {
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.CAUTIOUS, ApprovalPolicy.Overrides.NONE);

        assertTrue(p.decide(call("search", Tier.READ)).isAllow());
        assertTrue(p.decide(call("download", Tier.WRITE)).needsConfirmation());
        assertTrue(p.decide(call("terminal", Tier.EXEC)).needsConfirmation());
    }

    @Test
    public void balancedOnlyAsksBeforeCommands() {
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.BALANCED, ApprovalPolicy.Overrides.NONE);

        assertTrue(p.decide(call("download", Tier.WRITE)).isAllow());
        assertTrue(p.decide(call("terminal", Tier.EXEC)).needsConfirmation());
    }

    @Test
    public void trustingNeverAsks() {
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.TRUSTING, ApprovalPolicy.Overrides.NONE);
        assertTrue(p.decide(call("terminal", Tier.EXEC)).isAllow());
    }

    @Test
    public void theReasonIsWrittenForAPersonNotForALog() {
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.CAUTIOUS, ApprovalPolicy.Overrides.NONE);
        String reason = p.decide(call("terminal", Tier.EXEC)).reason();

        // "EXEC" means nothing to someone holding a phone.
        assertFalse(reason, reason.contains("EXEC"));
        assertTrue(reason, reason.contains("runs a command"));
    }

    @Test
    public void theModeCanChangeAtRuntime() {
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.CAUTIOUS, ApprovalPolicy.Overrides.NONE);
        assertTrue(p.decide(call("download", Tier.WRITE)).needsConfirmation());

        p.setMode(ApprovalMode.TRUSTING);
        assertTrue(p.decide(call("download", Tier.WRITE)).isAllow());
    }

    // -------------------------------------------------------- per-tool rules

    @Test
    public void alwaysAllowRelaxesTheModeForOneToolOnly() {
        ApprovalPolicy.MapOverrides ov = new ApprovalPolicy.MapOverrides();
        ov.set("download", ApprovalPolicy.Rule.ALWAYS_ALLOW);
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.CAUTIOUS, ov);

        ApprovalDecision allowed = p.decide(call("download", Tier.WRITE));
        assertTrue(allowed.isAllow());
        assertEquals(ApprovalDecision.Source.USER_OVERRIDE, allowed.source());

        // The blast radius is one tool.
        assertTrue(p.decide(call("terminal", Tier.EXEC)).needsConfirmation());
    }

    @Test
    public void neverBeatsEvenTheMostPermissiveMode() {
        ApprovalPolicy.MapOverrides ov = new ApprovalPolicy.MapOverrides();
        ov.set("terminal", ApprovalPolicy.Rule.NEVER);
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.TRUSTING, ov);

        assertTrue(p.decide(call("terminal", Tier.EXEC)).isDeny());
    }

    @Test
    public void clearingARuleHandsTheDecisionBackToTheMode() {
        ApprovalPolicy.MapOverrides ov = new ApprovalPolicy.MapOverrides();
        ov.set("download", ApprovalPolicy.Rule.ALWAYS_ALLOW);
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.CAUTIOUS, ov);
        assertTrue(p.decide(call("download", Tier.WRITE)).isAllow());

        ov.set("download", ApprovalPolicy.Rule.ASK_EACH_TIME);
        assertTrue(p.decide(call("download", Tier.WRITE)).needsConfirmation());
    }

    @Test
    public void toolNamesAreMatchedCaseInsensitively() {
        ApprovalPolicy.MapOverrides ov = new ApprovalPolicy.MapOverrides();
        ov.set("Download", ApprovalPolicy.Rule.ALWAYS_ALLOW);
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.CAUTIOUS, ov);

        assertTrue(p.decide(call("download", Tier.WRITE)).isAllow());
        assertTrue(p.decide(call("DOWNLOAD", Tier.WRITE)).isAllow());
    }

    /**
     * The prompt records "always allow" and the policy reads it. If they hold
     * different instances the user ticks the box and keeps being asked.
     */
    @Test
    public void overridesCanBeBoundAfterConstruction() {
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.CAUTIOUS, ApprovalPolicy.Overrides.NONE);
        assertTrue(p.decide(call("download", Tier.WRITE)).needsConfirmation());

        ApprovalPolicy.MapOverrides ov = new ApprovalPolicy.MapOverrides();
        ov.set("download", ApprovalPolicy.Rule.ALWAYS_ALLOW);
        p.setOverrides(ov);

        assertTrue(p.decide(call("download", Tier.WRITE)).isAllow());
    }

    // ---------------------------------------------------------------- safety

    @Test
    public void aNullCallIsDeniedRatherThanAllowed() {
        ApprovalPolicy p = new ApprovalPolicy(ApprovalMode.TRUSTING, ApprovalPolicy.Overrides.NONE);
        assertTrue(p.decide(null).isDeny());
    }

    @Test
    public void anUnknownModeNameFallsBackToTheCautiousDefault() {
        assertEquals(ApprovalMode.CAUTIOUS, ApprovalMode.fromName("nonsense"));
        assertEquals(ApprovalMode.CAUTIOUS, ApprovalMode.fromName(null));
        assertEquals(ApprovalMode.TRUSTING, ApprovalMode.fromName(" trusting "));
    }
}
