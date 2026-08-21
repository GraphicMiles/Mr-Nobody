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
 * Asks the foreground user to approve one tool call.
 *
 * <p>The worker waits for a bounded answer and always fails closed. A prompt is
 * represented by a live session shared by the worker and UI callbacks: timeout,
 * interruption, Activity replacement, or dismissal invalidates that session
 * and dismisses its dialog, so a stale tap can never approve later work.
 */
public final class ApprovalPrompt implements ToolPipeline.Confirmer {

    private static final long TIMEOUT_MS = 120_000L;

    /** The foreground activity, or null. */
    private static volatile Activity host;

    /** At most one actionable approval dialog process-wide. */
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();

    private final ApprovalPolicy.MapOverrides overrides;

    public ApprovalPrompt(ApprovalPolicy.MapOverrides overrides) {
        this.overrides = overrides;
    }

    /** Called from the activity lifecycle so prompts never outlive their host. */
    public static void setHost(Activity activity) {
        Activity previous = host;
        host = activity;
        if (previous != null && previous != activity) expireFor(previous);
    }

    public static void clearHost(Activity activity) {
        if (host == activity) host = null;
        expireFor(activity);
    }

    @Override
    public Confirmation confirm(ToolCall call, String reason) {
        Activity activity = host;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return Confirmation.UNAVAILABLE;
        }

        Session session = new Session(activity);
        if (!ACTIVE.compareAndSet(null, session)) {
            // Never stack two modals or let one answer be consumed by another call.
            return Confirmation.UNAVAILABLE;
        }

        try {
            activity.runOnUiThread(() -> show(session, call, reason));
        } catch (Throwable t) {
            ErrorLog.record("approval prompt could not reach UI: " + t);
            session.finish(false, false, false);
        }

        try {
            if (!session.answered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                ErrorLog.record("approval timed out, parking: " + call.summary());
                expire(session);
                return Confirmation.UNAVAILABLE;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            expire(session);
            return Confirmation.UNAVAILABLE;
        }

        if (!session.answeredByUser.get()) return Confirmation.UNAVAILABLE;
        boolean allowed = session.approved.get();
        if (allowed && session.remember && overrides != null) {
            // Session-only by design: restart returns to configured policy.
            overrides.set(call.tool(), ApprovalPolicy.Rule.ALWAYS_ALLOW);
        }
        return allowed ? Confirmation.ALLOWED : Confirmation.DENIED;
    }

    private static void show(Session session, ToolCall call, String reason) {
        if (!session.live.get() || host != session.activity
                || session.activity.isFinishing() || session.activity.isDestroyed()) {
            session.finish(false, false, false);
            return;
        }
        try {
            final boolean[] always = {false};
            AlertDialog dialog = new AlertDialog.Builder(session.activity)
                    .setTitle("Allow this action?")
                    .setMessage(call.summary()
                            + "\n\n" + capitalise(reason)
                            + "\n\nNothing runs unless you allow it.")
                    .setPositiveButton("Allow", (d, w) ->
                            session.finish(true, true, always[0]))
                    .setNegativeButton("Deny", (d, w) ->
                            session.finish(false, true, false))
                    .setOnCancelListener(d -> session.finish(false, true, false))
                    .setMultiChoiceItems(
                            new CharSequence[]{"Allow " + call.tool() + " for this app session"},
                            new boolean[]{false},
                            (d, which, checked) -> always[0] = checked)
                    .setCancelable(true)
                    .create();
            session.dialog.set(dialog);
            dialog.setOnDismissListener(d -> session.finish(false, false, false));
            if (session.live.get()) dialog.show();
            else dialog.dismiss();
        } catch (Throwable t) {
            ErrorLog.record("approval prompt failed: " + t);
            session.finish(false, false, false);
        }
    }

    private static void expireFor(Activity activity) {
        Session session = ACTIVE.get();
        if (session != null && session.activity == activity) expire(session);
    }

    private static void expire(Session session) {
        session.finish(false, false, false);
        try {
            session.activity.runOnUiThread(() -> {
                AlertDialog dialog = session.dialog.getAndSet(null);
                if (dialog != null && dialog.isShowing()) {
                    try { dialog.dismiss(); } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) {
            // The Activity is already gone; the session is still invalidated.
        }
    }

    private static final class Session {
        final Activity activity;
        final CountDownLatch answered = new CountDownLatch(1);
        final AtomicBoolean live = new AtomicBoolean(true);
        final AtomicBoolean approved = new AtomicBoolean(false);
        final AtomicBoolean answeredByUser = new AtomicBoolean(false);
        final AtomicReference<AlertDialog> dialog = new AtomicReference<>();
        volatile boolean remember;

        Session(Activity activity) {
            this.activity = activity;
        }

        void finish(boolean allow, boolean byUser, boolean rememberChoice) {
            if (!live.compareAndSet(true, false)) return;
            approved.set(allow);
            answeredByUser.set(byUser);
            remember = rememberChoice;
            ACTIVE.compareAndSet(this, null);
            answered.countDown();
        }
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "This action needs your approval.";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }
}
