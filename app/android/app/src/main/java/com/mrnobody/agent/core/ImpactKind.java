package com.mrnobody.agent.core;

/**
 * Finer risk than READ/WRITE/EXEC, for the actions the brief names.
 *
 * <p>The pipeline still judges on {@link Tier}. This says <em>why</em> a
 * WRITE/EXEC call is that tier, so delete/pay always confirm and a draft
 * does not.
 */
public enum ImpactKind {

    OBSERVE,
    DRAFT,
    SEND,
    PUBLISH,
    FINALIZE,
    DELETE,
    PAY;

    /** True when even TRUSTING or a tool-wide session grant must still ask. */
    public boolean alwaysConfirm() {
        return this == DELETE || this == PAY || this == FINALIZE;
    }

    public static ImpactKind of(String tool, String action, String text) {
        String a = action == null ? "" : action.toLowerCase();
        String namedTool = tool == null ? "" : tool.toLowerCase();
        String blob = ((text == null ? "" : text) + " " + a).toLowerCase();
        if ("design".equals(namedTool)
                && ("export".equals(a) || "commit".equals(a) || "publish".equals(a))) {
            return FINALIZE;
        }
        if (containsAny(blob, "delete", "remove post", "unsend", "deactivate")) {
            return DELETE;
        }
        if (containsAny(blob, "pay", "purchase", "checkout", "buy now", "place order",
                "add payment")) {
            return PAY;
        }
        if (containsAny(blob, "publish", "post tweet", "tweet this", "share publicly")) {
            return PUBLISH;
        }
        if (containsAny(blob, "send", "submit", "apply", "reply all")) {
            return SEND;
        }
        if ("submit".equals(a) || "upload".equals(a)) return SEND;
        if ("type".equals(a) || "select".equals(a)) return DRAFT;
        if ("click".equals(a)) {
            if (containsAny(blob, "send", "submit", "publish", "buy", "pay", "delete")) {
                return SEND;
            }
            return DRAFT;
        }
        return OBSERVE;
    }

    private static boolean containsAny(String hay, String... needles) {
        for (String n : needles) {
            if (hay.contains(n)) return true;
        }
        return false;
    }
}
