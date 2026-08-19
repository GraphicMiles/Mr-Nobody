package com.mrnobody.agent.browser;

/**
 * Unofficial X password login (Twikit-style) is a fail-closed gate.
 *
 * <p>It is off by default and cannot be turned on. A Settings bit, a
 * debug flag, or a future tool call must not grow a password path —
 * X's unofficial login is not something this app will do.
 *
 * <p>Users who want to automate <em>their own</em> account grant a
 * session: sign in in a visible tab, or paste a Cookie-Editor export.
 * {@link AccountStore} injects that grant. There is no username /
 * password / email login here on purpose.
 */
public final class UnofficialXLogin {

    /** Pref name, kept so a flipped bit is still ignored. */
    public static final String SETTING_KEY = "unofficial_x_login";

    private UnofficialXLogin() {
    }

    /** Always false. Delegates to the restricted catalog (active=false). */
    public static boolean isEnabled() {
        return com.mrnobody.agent.policy.RestrictedTools.isActive(
                com.mrnobody.agent.policy.RestrictedTools.TWIKIT);
    }

    /**
     * Always false. {@code userPref} is accepted so a caller that
     * read Settings cannot accidentally enable login by passing it through.
     */
    public static boolean isEnabled(boolean userPref) {
        return isEnabled() && userPref;
    }

    /** Why a Twikit-style action was refused. Safe to show in the UI. */
    public static String refuse(String action) {
        String what = action == null || action.trim().isEmpty() ? "login" : action.trim();
        return "Unofficial X " + what + " is off. "
                + "Sign in in a visible tab and grant the session, "
                + "or paste a Cookie-Editor export.";
    }
}
