package com.mrnobody.agent.planner;

import java.util.Locale;

/**
 * A page that is asking the user to sign in, not answering the task.
 *
 * <p>Different from a Cloudflare challenge: the honest next step is a
 * grant (visible tab or Cookie-Editor), not a retry.
 */
public final class LoginWall {

    private static final String[] MARKERS = {
            "sign in to continue",
            "log in to continue",
            "login to continue",
            "you must be logged in",
            "create an account to",
            "please sign in",
            "please log in",
            "authentication required",
            "sign in with",
            "already have an account",
    };

    private LoginWall() {
    }

    public static boolean isLogin(String htmlOrText) {
        if (htmlOrText == null || htmlOrText.isEmpty()) return false;
        String head = htmlOrText.substring(0, Math.min(htmlOrText.length(), 12_000))
                .toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String m : MARKERS) {
            if (head.contains(m)) hits++;
        }
        return hits >= 1 && head.length() < 80_000;
    }

    public static String message() {
        return "This page needs a signed-in session. Open it in a tab, sign in, "
                + "then grant the site — or paste a Cookie-Editor export.";
    }
}
