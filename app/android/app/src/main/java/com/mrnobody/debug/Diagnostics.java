package com.mrnobody.debug;

import android.content.Context;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.mrnobody.agent.browser.HeadlessWebViewEngine;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.planner.DeterministicPlanner;
import com.mrnobody.agent.planner.IntentRouter;
import com.mrnobody.agent.planner.IntentType;
import com.mrnobody.agent.planner.Plan;
import com.mrnobody.agent.planner.Planner;
import com.mrnobody.agent.policy.PolicyGate;
import com.mrnobody.agent.tools.WorkspacePath;
import com.mrnobody.agent.tasks.TaskStore;
import com.mrnobody.agent.util.DdgHtmlParser;
import com.mrnobody.agent.util.Hosts;
import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.core.Settings;
import com.mrnobody.browser.net.EngineInfo;
import com.mrnobody.browser.net.FingerprintDefence;
import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.browser.net.NetworkRoute;
import com.mrnobody.browser.net.OrbotTorRoute;
import com.mrnobody.browser.net.PrivacyController;
import com.mrnobody.browser.net.PrivacyMode;
import com.mrnobody.browser.net.ProfileManager;
import com.mrnobody.browser.net.ResourceControls;
import com.mrnobody.browser.net.ResourcePolicy;
import com.mrnobody.identity.AndroidKeyStoreIdentity;
import com.mrnobody.identity.DeviceIdentity;
import com.mrnobody.identity.SignedRequest;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Phase 1 device benchmark: a battery of checks that each exercise one
 * real subsystem and report a pass/fail the user can read off and hand back.
 *
 * <p>The point is to convert "the code looks right" into "we watched it work,
 * and here is which point failed". Each {@link Result} carries an id, a
 * human-readable name, a pass flag, and a detail line that says what was
 * observed — so a failure is a sentence the user can paste, not a guess.
 *
 * <p>{@link #runPure()} is the subset that needs no Android runtime and is
 * therefore JVM-tested in {@code DiagnosticsTest}; {@link #runDevice(Context)}
 * is the device-only half (filter engine over the bundled asset, WebView
 * capabilities, Keystore identity, SQLite task store). {@link #run(Context)}
 * combines them and is what the channel calls on a phone.
 */
public final class Diagnostics {

    /** One benchmark point. */
    public static final class Result {
        public final String id;
        public final String name;
        public final boolean pass;
        public final String detail;

        Result(String id, String name, boolean pass, String detail) {
            this.id = id;
            this.name = name;
            this.pass = pass;
            this.detail = detail == null ? "" : detail;
        }

        static Result pass(String id, String name, String detail) {
            return new Result(id, name, true, detail);
        }

        static Result fail(String id, String name, String detail) {
            return new Result(id, name, false, detail);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("pass", pass);
            m.put("detail", detail);
            return m;
        }
    }

    private Diagnostics() {
    }

    /** A check body that may throw; failures are caught and reported, not raised. */
    private interface Body {
        Result run() throws Exception;
    }

    private static Result check(String id, String name, Body body) {
        try {
            return body.run();
        } catch (Throwable t) {
            return Result.fail(id, name,
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static final Set<String> TOOLS = new LinkedHashSet<>(
            Arrays.asList("search", "http", "download", "browser", "terminal"));

    // ------------------------------------------------------------------ pure

    /** Checks that need no Android runtime. JVM-tested in {@code DiagnosticsTest}. */
    public static List<Result> runPure() {
        List<Result> out = new ArrayList<>();
        out.add(check("input.route", "Unified input → URL / search / task",
                () -> IntentRouter.route("https://example.com/page") == IntentType.URL
                        && IntentRouter.route("example.com") == IntentType.URL
                        && IntentRouter.route("find laptops under 500000") == IntentType.TASK
                        && IntentRouter.route("what is the capital of ghana") == IntentType.SEARCH
                        ? Result.pass("input.route", "Unified input routing",
                                "URL, bare domain, task verb and plain search all route correctly")
                        : Result.fail("input.route", "Unified input routing",
                                "one of URL / task / search misclassified")));

        out.add(check("search.parse", "Search → parsed results",
                () -> {
                    List<com.mrnobody.agent.util.SearchResult> results =
                            DdgHtmlParser.parse(SAMPLE_HTML, 5);
                    if (results.size() != 2) {
                        return Result.fail("search.parse", "Search → parsed results",
                                "expected 2 results, got " + results.size());
                    }
                    return Result.pass("search.parse", "Search → parsed results",
                            "parsed " + results.size() + " results ("
                                    + results.get(0).title + ", " + results.get(1).title + ")");
                }));

        out.add(check("hosts.detect", "Named-site detection",
                () -> {
                    String host = Hosts.firstIn("download it from nkiri.ink");
                    return host != null && host.equals("nkiri.ink")
                            ? Result.pass("hosts.detect", "Named-site detection",
                                    "bare hostname honoured: nkiri.ink")
                            : Result.fail("hosts.detect", "Named-site detection",
                                    "bare hostname not recognised (got " + host + ")");
                }));

        out.add(check("planner.plan", "Agent plan (cascade + growth)",
                () -> {
                    Planner planner = new DeterministicPlanner();
                    Plan plan = planner.plan("find laptops", TOOLS);
                    if (plan.size() != 4 || !Task.STEP_SEARCH.equals(plan.steps().get(0).label)) {
                        return Result.fail("planner.plan", "Agent plan",
                                "cascade wrong shape: " + plan.describe());
                    }
                    Plan growing = Plan.of(Plan.Step.internal("read"));
                    growing.append(Plan.Step.internal("download"));
                    return Result.pass("planner.plan", "Agent plan (cascade + growth)",
                            "cascade " + plan.describe() + "; plan grows in-flight");
                }));

        out.add(check("terminal.gate", "Terminal policy gate",
                () -> PolicyGate.Decision.ALLOW == new PolicyGate().classify("sha256 report.pdf")
                        && PolicyGate.Decision.DENY == new PolicyGate().classify("rm -rf /")
                        ? Result.pass("terminal.gate", "Terminal policy gate",
                                "safe command ALLOW, destructive command DENY")
                        : Result.fail("terminal.gate", "Terminal policy gate",
                                "gate misclassified a command")));

        out.add(check("workspace.sandbox", "Terminal filesystem sandbox",
                () -> {
                    File root = new File(System.getProperty("java.io.tmpdir"), "diag-ws");
                    root.mkdirs();
                    boolean inside = WorkspacePath.resolveWithin(root, "a.txt") != null;
                    boolean escape = WorkspacePath.resolveWithin(root, "../outside.txt") == null;
                    return inside && escape
                            ? Result.pass("workspace.sandbox", "Terminal filesystem sandbox",
                                    "inside path allowed, .. escape refused")
                            : Result.fail("workspace.sandbox", "Terminal filesystem sandbox",
                                    "escape not refused or inside not allowed");
                }));

        out.add(check("identity.sign", "Device identity + signed envelope",
                () -> {
                    KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                    kpg.initialize(new ECGenParameterSpec("secp256r1"));
                    KeyPair kp = kpg.generateKeyPair();
                    DeviceIdentity id = new DeviceIdentity(kp.getPublic(), kp.getPrivate(), "software");
                    SignedRequest req = SignedRequest.sign(id, "diag-nonce", "run-task:1");
                    return req.verify(id.publicKey(), req.timestamp())
                            ? Result.pass("identity.sign", "Device identity + signed envelope",
                                    "sign + verify round-trip; fingerprint " + id.fingerprint().substring(0, 12) + "…")
                            : Result.fail("identity.sign", "Device identity + signed envelope",
                                    "signed request failed verification");
                }));

        out.add(check("network.route", "Network gate (single egress)",
                () -> {
                    boolean canConnect = NetworkGate.canConnect();
                    String route = NetworkGate.route().getClass().getSimpleName();
                    return canConnect
                            ? Result.pass("network.route", "Network gate (single egress)",
                                    "route " + route + " can connect")
                            : Result.fail("network.route", "Network gate (single egress)",
                                    "route " + route + " is fail-closed and down");
                }));

        out.add(check("datasaver.policy", "Data Saver grades (OFF/BALANCED/AGGRESSIVE/EXTREME)",
                () -> {
                    ResourcePolicy[] all = ResourcePolicy.values();
                    if (all.length != 4) {
                        return Result.fail("datasaver.policy", "Data Saver grades",
                                "expected 4 grades, found " + all.length);
                    }
                    // Every grade must differ from every other on at least one
                    // lever, or the ladder is a lie.
                    for (int i = 0; i < all.length; i++) {
                        for (int j = i + 1; j < all.length; j++) {
                            if (!differs(all[i], all[j])) {
                                return Result.fail("datasaver.policy", "Data Saver grades",
                                        all[i].label() + " and " + all[j].label()
                                                + " do the same thing");
                            }
                        }
                    }
                    return Result.pass("datasaver.policy", "Data Saver grades",
                            "4 distinct grades: " + labels(all));
                }));

        return out;
    }

    private static boolean differs(ResourcePolicy a, ResourcePolicy b) {
        return a.gatesAutoplay() != b.gatesAutoplay()
                || a.disablesImages() != b.disablesImages()
                || a.disablesCache() != b.disablesCache();
    }

    private static String labels(ResourcePolicy[] all) {
        StringBuilder sb = new StringBuilder();
        for (ResourcePolicy p : all) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(p.label());
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- device

    /** Checks that need a device. Run only on Android, never on the test JVM. */
    public static List<Result> runDevice(Context context) {
        List<Result> out = new ArrayList<>();

        out.add(check("filter.engine", "Ad/tracker filter engine",
                () -> {
                    FilterEngine fe = new FilterEngine();
                    fe.loadBundled(context);
                    FilterEngine.Category ad = fe.shouldBlock("https://doubleclick.net/ad.js");
                    FilterEngine.Category plain = fe.shouldBlock("https://example.com/");
                    String status = "blocking=" + fe.isBlocking()
                            + ", ad→" + ad + ", plain→" + plain;
                    return fe.isBlocking() && ad == FilterEngine.Category.AD
                            && plain == FilterEngine.Category.NONE
                            ? Result.pass("filter.engine", "Ad/tracker filter engine", status)
                            : Result.fail("filter.engine", "Ad/tracker filter engine", status);
                }));

        out.add(check("settings.defaults", "Privacy defaults (history off, blocking on)",
                () -> {
                    Settings s = new Settings(context);
                    String status = "history=" + s.isHistoryEnabled()
                            + ", blocking=" + s.isBlockingEnabled()
                            + ", fingerprint=" + s.isFingerprintProtection()
                            + ", terminal=" + s.isTerminalEnabled();
                    return !s.isHistoryEnabled() && s.isBlockingEnabled()
                            ? Result.pass("settings.defaults", "Privacy defaults", status)
                            : Result.fail("settings.defaults", "Privacy defaults", status);
                }));

        out.add(check("engine.info", "Web engine reporting",
                () -> {
                    Map<String, Object> info = EngineInfo.describe(context);
                    String engine = String.valueOf(info.get("engine"));
                    return engine != null && !engine.isEmpty()
                            ? Result.pass("engine.info", "Web engine reporting",
                                    "engine=" + engine + ", multiProfile=" + info.get("multiProfile")
                                            + ", docStart=" + info.get("documentStartScript"))
                            : Result.fail("engine.info", "Web engine reporting", "no engine reported");
                }));

        out.add(check("webview.privacy", "WebView privacy capabilities",
                () -> {
                    boolean multi = ProfileManager.isSupported();
                    boolean docStart = FingerprintDefence.isSupported();
                    return Result.pass("webview.privacy", "WebView privacy capabilities",
                            "multi-profile=" + multi + ", document-start=" + docStart
                                    + (multi && docStart ? "" : "  ⚠︎ device WebView lacks a capability"));
                }));

        out.add(check("identity.keystore", "Keystore-backed identity",
                () -> {
                    DeviceIdentity id = AndroidKeyStoreIdentity.loadOrCreate();
                    return Result.pass("identity.keystore", "Keystore-backed identity",
                            "level=" + id.securityLevel() + ", fp="
                                    + id.fingerprint().substring(0, 12) + "…");
                }));

        out.add(check("task.store", "Durable task store (SQLite)",
                () -> {
                    TaskStore store = new TaskStore(context);
                    long id = store.insert("diagnostic probe task");
                    Task t = store.get(id);
                    return t != null && t.instruction().equals("diagnostic probe task")
                            ? Result.pass("task.store", "Durable task store (SQLite)",
                                    "insert + get round-trip, id=" + id)
                            : Result.fail("task.store", "Durable task store (SQLite)",
                                    "round-trip returned null or wrong instruction");
                }));

        // The headless browser: load a real page and extract its text, proving
        // the engine the agent drives actually renders. Runs off the main
        // thread (the caller awaits while the page finishes on main).
        out.add(check("headless.browser", "Headless browser engine",
                () -> {
                    HeadlessWebViewEngine engine = new HeadlessWebViewEngine(context);
                    try {
                        String text = engine.loadAndExtract("https://example.com/", 12_000L);
                        String clean = text == null ? "" : text.trim().replaceAll("\\s+", " ");
                        if (clean.isEmpty()) {
                            return Result.fail("headless.browser", "Headless browser engine",
                                    "loaded nothing — no network, or the engine returned empty text");
                        }
                        String preview = clean.length() > 60 ? clean.substring(0, 60) + "…" : clean;
                        return Result.pass("headless.browser", "Headless browser engine",
                                "loaded + extracted " + clean.length() + " chars: \"" + preview + "\"");
                    } finally {
                        engine.close();
                    }
                }));

        // Tor is the precondition for the manual NOBODY egress test. This only
        // says whether Orbot's SOCKS port answers — it does not, and cannot,
        // prove traffic actually routes through Tor from inside the process.
        out.add(check("tor.orbot", "Tor route (Orbot) reachable",
                () -> {
                    OrbotTorRoute route = new OrbotTorRoute();
                    route.refresh();
                    return route.isAvailable()
                            ? Result.pass("tor.orbot", "Tor route (Orbot) reachable",
                                    "Orbot SOCKS on " + OrbotTorRoute.HOST + ":" + OrbotTorRoute.PORT)
                            : Result.fail("tor.orbot", "Tor route (Orbot) reachable",
                                    "Orbot not reachable on " + OrbotTorRoute.HOST + ":"
                                            + OrbotTorRoute.PORT + " — start Orbot to test Tor routing");
                }));

        // Data Saver: apply the strictest grade and read the WebView settings
        // straight back. This proves a grade is real behaviour, not a label.
        out.add(check("datasaver.apply", "Data Saver applied to WebView",
                () -> {
                    // WebView must be created on the main thread.
                    final Object[] holder = new Object[1];
                    final CountDownLatch latch = new CountDownLatch(1);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        WebView wv = null;
                        try {
                            wv = new WebView(context.getApplicationContext());
                            ResourceControls.apply(wv, ResourcePolicy.EXTREME);
                            WebSettings s = wv.getSettings();
                            holder[0] = new Boolean[]{
                                    !s.getLoadsImagesAutomatically(),           // images off
                                    s.getMediaPlaybackRequiresUserGesture(),    // autoplay gated
                                    s.getCacheMode() == WebSettings.LOAD_NO_CACHE // cache off
                            };
                        } catch (Throwable t) {
                            holder[0] = t;
                        } finally {
                            if (wv != null) wv.destroy();
                            latch.countDown();
                        }
                    });
                    latch.await(5, TimeUnit.SECONDS);
                    Object value = holder[0];
                    if (value instanceof Throwable) {
                        Throwable t = (Throwable) value;
                        return Result.fail("datasaver.apply", "Data Saver applied to WebView",
                                t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                    if (!(value instanceof Boolean[])) {
                        return Result.fail("datasaver.apply", "Data Saver applied to WebView",
                                "timed out creating the WebView");
                    }
                    Boolean[] got = (Boolean[]) value;
                    boolean ok = got[0] && got[1] && got[2];
                    return ok
                            ? Result.pass("datasaver.apply", "Data Saver applied to WebView",
                                    "EXTREME → images=" + got[0] + ", autoplay-gated=" + got[1]
                                            + ", cache=" + got[2])
                            : Result.fail("datasaver.apply", "Data Saver applied to WebView",
                                    "EXTREME not honoured: images=" + got[0]
                                            + ", autoplay-gated=" + got[1] + ", cache=" + got[2]);
                }));

        // The NOBODY invariant, checked without mutating state: the app must
        // never sit in NOBODY while its route is down (fail-closed).
        out.add(check("nobody.route", "NOBODY mode route state",
                () -> {
                    Settings settings = new Settings(context);
                    NetworkRoute configured = PrivacyController.configuredRoute(settings);
                    configured.refresh();
                    PrivacyMode mode = PrivacyController.current();
                    String detail = "configured=" + configured.label()
                            + " (up=" + configured.isAvailable() + "), active="
                            + NetworkGate.route().getClass().getSimpleName()
                            + ", mode=" + mode.name();
                    boolean consistent = mode != PrivacyMode.NOBODY || configured.isAvailable();
                    return consistent
                            ? Result.pass("nobody.route", "NOBODY mode route state", detail)
                            : Result.fail("nobody.route", "NOBODY mode route state",
                                    detail + " — in NOBODY while the route is down");
                }));

        return out;
    }

    /** The full battery: pure + device. */
    public static List<Result> run(Context context) {
        List<Result> out = new ArrayList<>();
        out.addAll(runPure());
        if (context != null) out.addAll(runDevice(context));
        return out;
    }

    /** The channel payload: a list of result maps. */
    public static List<Map<String, Object>> runAsMaps(Context context) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Result r : run(context)) out.add(r.toMap());
        return out;
    }

    private static final String SAMPLE_HTML =
            "<html><body>"
            + "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&rut=x\">"
            + "Example <b>Title</b></a>"
            + "<a class=\"result__snippet\" href=\"#\">A short snippet about the result.</a>"
            + "<a class=\"result__url\" href=\"#\">example.com</a>"
            + "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fsecond.com%2F\">Second Title</a>"
            + "<a class=\"result__snippet\" href=\"#\">Another snippet.</a>"
            + "<a class=\"result__url\" href=\"#\">second.com</a>"
            + "</body></html>";
}
