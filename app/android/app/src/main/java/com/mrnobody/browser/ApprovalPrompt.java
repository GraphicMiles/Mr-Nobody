package com.mrnobody.browser;

import android.app.Activity;
import android.app.AlertDialog;

import com.mrnobody.agent.core.Confirmation;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolPipeline;
import com.mrnobody.agent.policy.ApprovalPolicy;
import com.mrnobody.debug.ErrorLog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asks the user to approve a tool call.
 *
 * <p>The pipeline has had a {@code Confirmer} seam since it was built and
 * nothing was ever attached, so every CONFIRM resolved to a refusal. The
 * policy gate's whole middle tier was unreachable and the terminal could run
 * exactly one command. This is the missing half.
 *
 * <p><b>Blocking, on purpose.</b> A tool call is a synchronous decision made
 * on a worker thread: the pipeline needs an answer before it can proceed, and
 * "assume yes while we ask" is the one behaviour a confirmation gate must not
 * have. The wait is bounded so a task cannot hang forever behind a dialog
 * nobody is looking at, and the timeout denies.
 *
 * <p><b>No activity means no approval.</b> When the app is in the background
 * there is nobody to ask, so the call is refused with an explanation rather
 * than queued invisibly or silently allowed.
 */
public final class ApprovalPrompt implements ToolPipeline.Confirmer {

    /**
     * How long to wait for an answer.
     *
     * <p>Longer than a person needs to read one sentence, shorter than a
     * background task should ever be stuck. On expiry the call is denied.
     */
    private static final long TIMEOUT_MS = 120_000L;

    /** The foreground activity, or null. Weakly held via a static setter. */
    private static volatile Activity host;

    private final ApprovalPolicy.MapOverrides overrides;

    public ApprovalPrompt(ApprovalPolicy.MapOverrides overrides) {
        this.overrides = overrides;
    }

    /** Called from the activity's lifecycle so we know whether anyone is there. */
    public static void setHost(Activity activity) {
        host = activity;
    }

    public static void clearHost(Activity activity) {
        if (host == activity) host = null;
    }

    @Override
    public Confirmation confirm(ToolCall call, String reason) {
        Activity activity = host;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            // Nobody is looking. The call must not run; the task should wait.
            return Confirmation.UNAVAILABLE;
        }

        final CountDownLatch answered = new CountDownLatch(1);
        final AtomicBoolean approved = new AtomicBoolean(false);
        final AtomicBoolean answeredByUser = new AtomicBoolean(false);
        final AtomicReference<Boolean> remember = new AtomicReference<>(Boolean.FALSE);

        activity.runOnUiThread(() -> {
            try {
                final boolean[] always = {false};

                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("Allow this action?")
                        // The exact call, not a category. The user is agreeing
                        // to this, so this is what they are shown.
                        .setMessage(call.summary()
                                + "\n\n" + capitalise(reason)
                                + "\n\nNothing runs unless you allow it.")
                        .setPositiveButton("Allow", (d, w) -> {
                            approved.set(true);
                            answeredByUser.set(true);
                            remember.set(always[0]);
                        })
                        .setNegativeButton("Deny", (d, w) -> {
                            approved.set(false);
                            answeredByUser.set(true);
                        })
                        .setOnCancelListener(d -> {
                            approved.set(false);
                            answeredByUser.set(true);
                        })
                        .setMultiChoiceItems(
                                new CharSequence[]{"Always allow " + call.tool()},
                                new boolean[]{false},
                                (d, which, checked) -> always[0] = checked)
                        .setCancelable(true)
                        .create();

                dialog.setOnDismissListener(d -> answered.countDown());
                dialog.show();
            } catch (Throwable t) {
                ErrorLog.record("approval prompt failed: " + t);
                approved.set(false);
                answered.countDown();
            }
        });

        try {
            if (!answered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                ErrorLog.record("approval timed out, denying: " + call.summary());
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        boolean allowed = approved.get();
        if (allowed && Boolean.TRUE.equals(remember.get()) && overrides != null) {
            // "Always allow" is a per-tool override, which is exactly the
            // escape hatch that makes a cautious default liveable.
            overrides.set(call.tool(), ApprovalPolicy.Rule.ALWAYS_ALLOW);
        }
        return allowed;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "This action needs your approval.";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }
}
