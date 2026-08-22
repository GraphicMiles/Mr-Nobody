package com.mrnobody.agent.design;

/** Conservative per-session caps below Canva's per-minute service limits. */
public final class DesignQuota {
    public enum Operation { CREATE, EDIT, EXPORT, POLL }

    public static final int MAX_CREATES = 4;
    public static final int MAX_EDITS = 20;
    public static final int MAX_EXPORTS = 4;
    public static final int MAX_POLLS = 60;

    private DesignQuota() { }

    public static int limit(Operation operation) {
        switch (operation) {
            case CREATE: return MAX_CREATES;
            case EDIT: return MAX_EDITS;
            case EXPORT: return MAX_EXPORTS;
            case POLL: default: return MAX_POLLS;
        }
    }
}
