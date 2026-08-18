package com.mrnobody.browser.net;

import java.util.Locale;

/**
 * Data Saver: how much the page may pull down.
 *
 * <p>Four grades, each additive on the resource axis. The levers are the three
 * things a WebView can genuinely restrict — autoplay, images, and caching —
 * and nothing else, because a grade that claims to do something the engine
 * cannot do is a grade that lies. Ads and trackers stay on their own switch
 * ({@code blocking_enabled}); this policy is about bytes the page fetches for
 * itself.
 *
 * <pre>
 * OFF        — nothing restricted
 * BALANCED   — autoplay requires a user gesture
 * AGGRESSIVE — + images disabled
 * EXTREME    — + caching disabled (every visit re-fetches)
 * </pre>
 *
 * <p>Pure Java on purpose: the grade-to-lever mapping is a decision that can
 * drift and be unit-tested without a device; {@code ResourceControls} applies
 * it to a real WebView.
 */
public enum ResourcePolicy {

    OFF("Off", "No restrictions. Pages load normally."),
    BALANCED("Balanced", "Autoplay off."),
    AGGRESSIVE("Aggressive", "Autoplay off, images off."),
    EXTREME("Extreme", "Autoplay off, images off, no caching.");

    private final String label;
    private final String description;

    ResourcePolicy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Autoplay is gated behind a user gesture for everything above OFF. */
    public boolean gatesAutoplay() {
        return this != OFF;
    }

    /** Images are not loaded for AGGRESSIVE and EXTREME. */
    public boolean disablesImages() {
        return this == AGGRESSIVE || this == EXTREME;
    }

    /** Caching is disabled only for EXTREME. */
    public boolean disablesCache() {
        return this == EXTREME;
    }

    /** Parse a persisted or UI-supplied name; unknown falls back to BALANCED. */
    public static ResourcePolicy fromName(String name) {
        if (name == null) return BALANCED;
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (ResourcePolicy p : values()) {
            if (p.name().equals(n)) return p;
        }
        return BALANCED;
    }
}
