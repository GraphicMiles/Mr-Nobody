package com.mrnobody.debug;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.planner.ClockSkill;
import com.mrnobody.agent.planner.IntentRouter;
import com.mrnobody.agent.planner.IntentType;
import com.mrnobody.agent.planner.OutcomeCheck;
import com.mrnobody.agent.planner.SearchSkills;
import com.mrnobody.agent.planner.ToolScope;
import com.mrnobody.agent.util.ReadableText;
import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.net.EmbeddedTor;
import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.browser.net.PrivacyController;
import com.mrnobody.browser.net.PrivacyMode;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one-tap device test suite (Dev mode → Benchmarks → Device suite).
 *
 * <p>Exists because the owner's manual retest list had grown to ~29 items ×
 * many minutes on a real phone. Everything here runs the REAL core on the
 * REAL device and network — the same engine instance, the same routes — and
 * turns an afternoon of tapping into one button and a copyable report. What
 * it deliberately does not do: fake taps on the Flutter UI. Screens still
 * get human eyes (the "Needs your eyes" checklist).
 *
 * <p>Checks run one at a time over the channel so the UI can show progress
 * and the user can stop between checks. Order matters: the Tor checks come
 * last because they change the device's network state, and the final check
 * restores whatever mode the suite found.
 */
public final class DeviceSuite {

    /** Wall ceilings per check — every check must end, pass or fail. */
    static final long AGENT_CHECK_MS = 90_000L;
    static final long DOWNLOAD_CHECK_MS = 150_000L;
    static final long TOR_CHECK_MS = 110_000L;

    /** The mode the suite found, restored by the final check. */
    private static volatile String modeBeforeSuite;

    private DeviceSuite() {
    }

    /** Ordered ids + names, so the panel can render pending rows up front. */
    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        add(out, "suite.clock", "Clock skill (local, zones, negatives)");
        add(out, "suite.routing", "Slash commands and task verbs");
        add(out, "suite.skills", "YouTube skill routing");
        add(out, "suite.scope", "Tool scope (research vs routed)");
        add(out, "suite.outcome", "Outcome checks (download, named site)");
        add(out, "suite.junk", "Boilerplate gate");
        add(out, "suite.agent_speed", "Live agent: question speed (network)");
        add(out, "suite.image_download", "Live agent: png icon download (network)");
        add(out, "suite.tor_bundled", "Built-in Tor packaged");
        add(out, "suite.tor_nobody", "Nobody mode reaches Tor (network, ~1 min)");
        add(out, "suite.tor_exit", "Traffic exits via Tor (check.torproject.org)");
        add(out, "suite.tor_restore", "Restore the mode the suite found");
        return out;
    }

    private static void add(List<Map<String, Object>> out, String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        out.add(m);
    }

    /** Run one check. Never throws — a crash here would eat the report. */
    public static Map<String, Object> run(Context context, String id) {
        String name = nameOf(id);
        try {
            switch (id == null ? "" : id) {
                case "suite.clock": return clock(id, name);
                case "suite.routing": return routing(id, name);
                case "suite.skills": return skills(id, name);
                case "suite.scope": return scope(id, name);
                case "suite.outcome": return outcome(id, name);
                case "suite.junk": return junk(id, name);
                case "suite.agent_speed": return agentSpeed(context, id, name);
                case "suite.image_download": return imageDownload(context, id, name);
                case "suite.tor_bundled": return torBundled(id, name);
                case "suite.tor_nobody": return torNobody(context, id, name);
                case "suite.tor_exit": return torExit(id, name);
                case "suite.tor_restore": return torRestore(id, name);
                default: return result(id, name, false, "unknown check id");
            }
        } catch (Throwable t) {
            return result(id, name, false,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String nameOf(String id) {
        for (Map<String, Object> m : describe()) {
            if (m.get("id").equals(id)) return String.valueOf(m.get("name"));
        }
        return String.valueOf(id);
    }

    private static Map<String, Object> result(String id, String name,
                                              boolean pass, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("pass", pass);
        m.put("detail", detail);
        return m;
    }

    // -------------------------------------------------------- instant checks

    private static Map<String, Object> clock(String id, String name) {
        String local = ClockSkill.answer("whats the time");
        String zone = ClockSkill.answer("what time is it in london");
        boolean localOk = local != null && local.contains("no network was used");
        boolean zoneOk = zone != null && zone.contains("Europe/London");
        boolean negOk = ClockSkill.answer("how old is messi") == null
                && ClockSkill.answer("best time to visit iceland") == null;
        boolean pass = localOk && zoneOk && negOk;
        return result(id, name, pass, pass
                ? "local + London zone answered; time-ish questions fell through"
                : "local=" + localOk + " zone=" + zoneOk + " negatives=" + negOk);
    }

    private static Map<String, Object> routing(String id, String name) {
        boolean slash = IntentRouter.route("/agent why is the sky blue") == IntentType.TASK
                && IntentRouter.route("/search cats") == IntentType.SEARCH
                && IntentRouter.route("/open example.com") == IntentType.URL
                && "why is the sky blue".equals(IntentRouter.payload("/agent why is the sky blue"));
        boolean verbs = true;
        for (String v : new String[]{"research the eiffel tower",
                "read about the roman empire", "use google search to find laptops",
                "look for cheap flights", "browse for wallpapers"}) {
            verbs &= IntentRouter.route(v) == IntentType.TASK;
        }
        boolean pass = slash && verbs;
        return result(id, name, pass, pass
                ? "slash commands + payload strip + BUG-7 verbs all route to task"
                : "slash=" + slash + " verbs=" + verbs);
    }

    private static Map<String, Object> skills(String id, String name) {
        boolean latest = "youtube.latest".equals(
                SearchSkills.route("latest video on youtube from mkbhd").id)
                && "youtube.latest".equals(
                        SearchSkills.route("get the latest video on mrbeast's youtube channel").id);
        boolean search = "youtube.search".equals(
                SearchSkills.route("search youtube for lofi mixes").id);
        boolean pass = latest && search;
        return result(id, name, pass, pass
                ? "from/on/possessive channel phrasings route to the YouTube skills"
                : "latest=" + latest + " search=" + search);
    }

    private static Map<String, Object> scope(String id, String name) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(Arrays.asList(
                "search", "http", "browser", "download", "terminal"));
        java.util.Set<String> research = ToolScope.research(false, all);
        java.util.Set<String> download = ToolScope.research(true, all);
        boolean pass = !research.contains("terminal") && !research.contains("download")
                && download.contains("download") && !download.contains("terminal")
                && ToolScope.routed("terminal").equals(java.util.Collections.singleton("terminal"));
        return result(id, name, pass, pass
                ? "questions get reading tools only; download joins on intent; terminal only routed"
                : "research=" + research + " download=" + download);
    }

    private static Map<String, Object> outcome(String id, String name) {
        List<String> reads = Arrays.asList("https://en.wikipedia.org/wiki/Cat");
        boolean download = OutcomeCheck.note("download the report", null, reads)
                .contains("no file was downloaded");
        boolean named = OutcomeCheck.note("cats from nkiri.ink", null, reads)
                .contains("nkiri.ink");
        boolean quiet = OutcomeCheck.note("cats from wikipedia.org", null, reads).isEmpty();
        boolean pass = download && named && quiet;
        return result(id, name, pass, pass
                ? "unmet download and unread named site are called out; met asks stay quiet"
                : "download=" + download + " named=" + named + " quiet=" + quiet);
    }

    private static Map<String, Object> junk(String id, String name) {
        boolean pass = !ReadableText.proseSentence(
                "Please enable JavaScript or switch to a supported browser to continue using this site.")
                && !ReadableText.proseSentence(
                        "We use cookies to improve your experience, accept all cookies to continue.")
                && ReadableText.proseSentence(
                        "The Eiffel Tower was completed in 1889 as the entrance to the fair.");
        return result(id, name, pass, pass
                ? "cookie/JS walls rejected; real prose accepted"
                : "a boilerplate gate misjudged a sentence");
    }

    // ------------------------------------------------- live agent (network)

    private static Map<String, Object> agentSpeed(Context context, String id, String name) {
        if (context == null || MrNobodyApp.agent() == null) {
            return result(id, name, false, "needs the running app (no engine here)");
        }
        Task task = new Task(0L, "how old is lionel messi");
        long started = System.currentTimeMillis();
        MrNobodyApp.agent().run(context, task, Cancellation.NONE);
        long elapsed = System.currentTimeMillis() - started;
        boolean completed = task.status() == Task.Status.COMPLETED;
        String answer = task.result() == null ? "" : task.result();
        boolean cited = answer.contains("[1]");
        boolean fast = elapsed <= AGENT_CHECK_MS;
        boolean pass = completed && cited && fast;
        return result(id, name, pass, (elapsed / 1000) + "s, "
                + (completed ? "completed" : "status " + task.status())
                + (cited ? ", cited sources" : ", NO citations")
                + (fast ? "" : " — over the " + (AGENT_CHECK_MS / 1000) + "s ceiling"));
    }

    private static Map<String, Object> imageDownload(Context context, String id, String name) {
        if (context == null || MrNobodyApp.agent() == null) {
            return result(id, name, false, "needs the running app (no engine here)");
        }
        Task task = new Task(0L, "download a png icon from pngtree");
        long started = System.currentTimeMillis();
        MrNobodyApp.agent().run(context, task, Cancellation.NONE);
        long elapsed = System.currentTimeMillis() - started;
        String answer = (task.result() == null ? "" : task.result())
                .toLowerCase(Locale.ROOT);
        boolean downloaded = answer.contains("download") && answer.contains(".png");
        boolean pass = task.status() == Task.Status.COMPLETED && downloaded
                && elapsed <= DOWNLOAD_CHECK_MS;
        return result(id, name, pass, (elapsed / 1000) + "s — "
                + (downloaded ? "a .png was resolved and enqueued"
                        : "no .png in the outcome: " + firstLine(task.result())));
    }

    private static String firstLine(String text) {
        if (text == null) return "(no result)";
        String t = text.trim();
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(0, nl);
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    // ------------------------------------------------------------ Tor checks

    private static Map<String, Object> torBundled(String id, String name) {
        boolean pass = EmbeddedTor.isBundled();
        return result(id, name, pass, pass
                ? "TorService and its runtime dependency are packaged"
                : "not packaged — Nobody is Orbot-only in this build");
    }

    private static Map<String, Object> torNobody(Context context, String id, String name) {
        if (context == null) return result(id, name, false, "needs the running app");
        modeBeforeSuite = PrivacyController.current().name();
        PrivacyController.Result r = MrNobodyApp.applyPrivacyMode(PrivacyMode.NOBODY);
        long started = System.currentTimeMillis();
        if (r.isFullyApplied()) {
            return result(id, name, true,
                    "applied immediately (a SOCKS listener was already up — Orbot or a warm TorService)");
        }
        if (!r.pending) {
            return result(id, name, false, "refused: " + r.problem);
        }
        while (System.currentTimeMillis() - started < TOR_CHECK_MS) {
            if (PrivacyController.current() == PrivacyMode.NOBODY) {
                return result(id, name, true, "auto-applied after "
                        + ((System.currentTimeMillis() - started) / 1000) + "s of bootstrap");
            }
            if (!PrivacyController.isTorPending()) break;
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String problem = PrivacyController.consumePendingProblem();
        return result(id, name, false, problem != null ? problem
                : "did not reach Nobody within " + (TOR_CHECK_MS / 1000) + "s");
    }

    private static Map<String, Object> torExit(String id, String name) {
        if (PrivacyController.current() != PrivacyMode.NOBODY) {
            return result(id, name, false, "Nobody is not active — run after the Nobody check");
        }
        try {
            HttpURLConnection conn = NetworkGate.openHttp("https://check.torproject.org/");
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "MrNobody/1.0");
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8_192];
                int n;
                while ((n = in.read(buf)) > 0 && sb.length() < 64_000) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            boolean onTor = sb.indexOf("Congratulations") >= 0;
            boolean notOnTor = sb.indexOf("not using Tor") >= 0;
            return result(id, name, onTor, onTor
                    ? "check.torproject.org confirms the exit is a Tor relay"
                    : (notOnTor ? "check.torproject.org says NOT on Tor — routing leak, screenshot this"
                            : "page fetched but verdict not found"));
        } catch (Exception e) {
            return result(id, name, false, "fetch through the gate failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> torRestore(String id, String name) {
        String before = modeBeforeSuite == null ? "NORMAL" : modeBeforeSuite;
        PrivacyController.Result r =
                MrNobodyApp.applyPrivacyMode(PrivacyMode.fromName(before));
        boolean pass = r.effective.name().equals(before) || r.pending;
        return result(id, name, pass, pass
                ? "device left in " + r.effective.name() + " as found"
                : "could not restore " + before + ": " + r.problem);
    }
}
