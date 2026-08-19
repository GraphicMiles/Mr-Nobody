package com.mrnobody.agent.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Restricted capabilities listed in Settings → Restricted tools.
 *
 * <p>Each tool has a real {@link #execute(String)} path. The first thing
 * that path does is read {@link #ACTIVE}. That bit is compiled {@code false}
 * and cannot be flipped by Settings, a pref, or a method argument. So the
 * code runs, and the user still cannot use the tool.
 *
 * <p>Grade is {@code off} / {@code safe} / {@code active}. These five stay
 * {@code off} because {@code active} is false.
 */
public final class RestrictedTools {

    /**
     * Master switch. Hard-false. A future honest capability would need a
     * source change, not a Settings tap.
     */
    public static final boolean ACTIVE = false;

    public enum Grade {
        OFF, SAFE, ACTIVE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final class Entry {
        public final String id;
        public final String title;
        public final String summary;
        public final Grade grade;
        public final boolean active;

        Entry(String id, String title, String summary) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.active = ACTIVE;
            this.grade = ACTIVE ? Grade.ACTIVE : Grade.OFF;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("summary", summary);
            m.put("grade", grade.wire());
            m.put("active", active);
            return m;
        }
    }

    public static final String TWIKIT = "twikit";
    public static final String STEALTH_CF = "stealth_cf";
    public static final String JINA_DEFAULT = "jina_default";
    public static final String SITE_DOWNLOADERS = "site_downloaders";
    public static final String PHONE_VMS = "phone_vms";

    private static final Entry[] ALL = {
            new Entry(TWIKIT,
                    "Twikit / unofficial X login",
                    "Password login against X's unofficial API. Off. Grant a tab session instead."),
            new Entry(STEALTH_CF,
                    "Stealth Cloudflare / Camoufox / TLS spoofing",
                    "Fingerprint spoofing and challenge solvers. Off. A visible tab is the honest path."),
            new Entry(JINA_DEFAULT,
                    "Jina Reader as the default fetch",
                    "Send every URL to a third-party reader. Off. Fetch stays on-device."),
            new Entry(SITE_DOWNLOADERS,
                    "Site-specific pirate downloaders",
                    "Host-locked file scrapers. Off. Generic resolve only."),
            new Entry(PHONE_VMS,
                    "Lightpanda / microsandbox on this phone",
                    "Ship a browser VM or sandbox runtime in the APK. Off."),
    };

    private RestrictedTools() {
    }

    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (Entry t : ALL) out.add(t);
        return out;
    }

    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry t : ALL) out.add(t.toMap());
        return out;
    }

    public static Entry find(String id) {
        if (id == null) return null;
        for (Entry t : ALL) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    /** Always false. Id is accepted so callers cannot pass a pref through. */
    public static boolean isActive(String id) {
        return ACTIVE;
    }

    /** Always false. */
    public static boolean isActive() {
        return ACTIVE;
    }

    /**
     * Run the tool. The method always executes. If {@link #ACTIVE} is false
     * (it is), the payload is not entered and the caller gets a refusal.
     */
    public static Map<String, Object> execute(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        Entry t = find(id);
        if (t == null) {
            out.put("id", id == null ? "" : id);
            out.put("ran", true);
            out.put("ok", false);
            out.put("active", false);
            out.put("grade", Grade.OFF.wire());
            out.put("reason", "unknown restricted tool");
            return out;
        }
        out.put("id", t.id);
        out.put("title", t.title);
        out.put("ran", true);
        out.put("active", t.active);
        out.put("grade", t.grade.wire());
        if (!t.active) {
            out.put("ok", false);
            out.put("reason", refuse(t));
            return out;
        }
        // Unreachable while ACTIVE is compiled false.
        out.putAll(payload(t));
        return out;
    }

    public static String refuse(String id) {
        Entry t = find(id);
        return t == null ? "unknown restricted tool" : refuse(t);
    }

    static String refuse(Entry t) {
        return t.title + " is off (active=false). Users cannot turn it on.";
    }

    /**
     * Payload if a tool were ever compiled active. Still does not implement
     * unofficial login, stealth solvers, Jina-as-default, pirate scrapers,
     * or on-device VMs — those stay out of this tree.
     */
    private static Map<String, Object> payload(Entry t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("reason", t.title + " has no payload in this build.");
        return m;
    }
}
