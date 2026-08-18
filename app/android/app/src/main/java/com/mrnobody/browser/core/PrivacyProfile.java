package com.mrnobody.browser.core;

/**
 * Privacy profiles (V2). A profile is a named bundle of settings that trades
 * compatibility for privacy. Selecting a profile applies its defaults; the
 * user can still override individual toggles afterward.
 *
 * All profile behavior is local. Nothing is uploaded.
 */
public enum PrivacyProfile {

    BALANCED("Balanced"),
    STRICT("Strict"),
    MAXIMUM("Maximum");

    private final String label;

    PrivacyProfile(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Apply this profile's defaults to the given settings. */
    public void apply(Settings settings) {
        switch (this) {
            case BALANCED:
                settings.setBlockingEnabled(true);
                settings.setJsEnabled(true);
                settings.setHistoryEnabled(false);
                settings.setParamStrippingEnabled(true);
                settings.setFingerprintProtection(false);
                break;
            case STRICT:
                settings.setBlockingEnabled(true);
                settings.setJsEnabled(true);
                settings.setHistoryEnabled(false);
                settings.setParamStrippingEnabled(true);
                settings.setFingerprintProtection(true);
                break;
            case MAXIMUM:
                settings.setBlockingEnabled(true);
                settings.setJsEnabled(false); // most sites break — user's choice
                settings.setHistoryEnabled(false);
                settings.setParamStrippingEnabled(true);
                settings.setFingerprintProtection(true);
                break;
        }
    }

    public static PrivacyProfile fromName(String name) {
        for (PrivacyProfile p : values()) {
            if (p.name().equalsIgnoreCase(name)) return p;
        }
        return BALANCED;
    }
}
