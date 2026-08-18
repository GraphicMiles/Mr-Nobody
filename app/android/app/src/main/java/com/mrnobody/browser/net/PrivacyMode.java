package com.mrnobody.browser.net;

/**
 * What the user is asking for, as one choice.
 *
 * <p>Named {@code PrivacyMode} rather than {@code PrivacyProfile} because
 * {@link com.mrnobody.browser.core.PrivacyProfile} already exists and means
 * something else — a bundle of blocking defaults (Balanced / Strict /
 * Maximum). These are different axes and both are real: a user can be in
 * PRIVATE with a Balanced filter profile. Reusing the word would have made
 * every later reference ambiguous.
 *
 * <p>The three modes are cumulative, and each states what it does <em>not</em>
 * do, because the gap between those is where privacy products mislead people.
 */
public enum PrivacyMode {

    /**
     * Ordinary browsing. Tracker and ad blocking, history off by default,
     * default WebView profile, direct network.
     */
    NORMAL("Normal",
            "Tracker and ad blocking. Your normal connection."),

    /**
     * A dedicated WebView profile: isolated cookies, storage and service
     * workers, destroyed when the session ends.
     *
     * <p>Storage isolation only. Same IP, same TLS fingerprint, same
     * everything a server sees at the network layer — which is why the
     * description says so rather than saying "private".
     */
    PRIVATE("Private",
            "Separate cookies and storage, erased when you close it. "
                    + "Your IP address is unchanged."),

    /**
     * Private, plus the traffic actually leaves by a different door: proxy or
     * Tor, fail-closed, with DNS resolved at the proxy.
     *
     * <p>The wording is the honest ceiling. Fingerprint resistance reduces
     * uniqueness; it does not confer anonymity, and Tor over a general-purpose
     * WebView is not Tor Browser.
     */
    NOBODY("Nobody",
            "Helps hide your IP and reduce persistent browser identification. "
                    + "It does not guarantee anonymity.");

    private final String label;
    private final String description;

    PrivacyMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    /** One honest sentence for the UI. Shown, not buried in a help page. */
    public String description() {
        return description;
    }

    /** True when this mode needs its own isolated WebView profile. */
    public boolean needsIsolatedProfile() {
        return this != NORMAL;
    }

    /** True when this mode must route through a privacy route. */
    public boolean needsPrivacyRoute() {
        return this == NOBODY;
    }

    /** True when fingerprint resistance should be injected. */
    public boolean needsFingerprintDefence() {
        return this == NOBODY;
    }

    public static PrivacyMode fromName(String name) {
        if (name != null) {
            for (PrivacyMode m : values()) {
                if (m.name().equalsIgnoreCase(name.trim())) return m;
            }
        }
        return NORMAL;
    }
}
