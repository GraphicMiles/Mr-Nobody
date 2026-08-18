package com.mrnobody.browser.blocking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Behavioural tests for blocking: real URLs, through the engine, against the
 * list we actually ship.
 *
 * <p>{@link BlocklistTest} exercises the matcher by calling
 * {@code list.ads.matchesHost(...)} directly. That proves the matcher works on
 * rules a test just added, and proves nothing about the shipping product,
 * because it never touches {@link FilterEngine#shouldBlock}, never parses
 * {@code blocklist.txt}, and never sees a URL in the form a page requests one.
 * Everything between the matcher and the request path — section parsing,
 * URL decomposition, the enabled flag, the counters — was untested.
 *
 * <p>That gap is not theoretical. {@code shouldBlock} can fail while the
 * matcher passes: a URL with a port, a scheme-relative reference, an uppercase
 * host, or a blocklist section header parsed into the wrong category all
 * produce a working matcher and an app that blocks nothing. Blocking silently
 * turning itself off is the single worst failure this product has, because
 * nothing about the UI changes when it happens.
 *
 * <p>These tests parse the asset the APK ships and assert on
 * {@code shouldBlock} directly. The one thing they cannot do off-device is
 * drive {@code WebViewClient.shouldInterceptRequest}, which needs a real
 * WebView — so {@code MrNobodyWebView}'s two lines of glue stay unverified
 * until the device matrix exists. Everything the glue calls is covered here.
 */
public class FilterEngineBehaviourTest {

    private FilterEngine engine;

    /**
     * Load the shipped blocklist without an Android {@code Context}.
     *
     * <p>{@link FilterEngine#loadBundled} reads through {@code getAssets()},
     * which does not exist on a JVM. {@code loadForTest} takes the same bytes
     * through the same parser the real load uses, so a section header or rule
     * the shipping parser mishandles is mishandled here too. Re-implementing
     * the loop in the test would have tested the copy.
     */
    @Before
    public void setUp() throws IOException {
        engine = new FilterEngine();
        try (java.io.InputStream in = Files.newInputStream(blocklistPath())) {
            engine.loadForTest(in);
        }
    }

    /** The asset the APK ships, found from either the module or the repo root. */
    private static Path blocklistPath() {
        Path fromModule = Paths.get("src/main/assets/blocklist.txt");
        if (Files.isReadable(fromModule)) return fromModule;
        return Paths.get("app/android/app/src/main/assets/blocklist.txt");
    }

    // ------------------------------------------------------------------
    // The list is real
    // ------------------------------------------------------------------

    /**
     * A list that failed to parse blocks nothing and looks exactly like a list
     * that parsed fine, so this is asserted before anything else.
     */
    @Test
    public void theShippedListActuallyLoaded() {
        assertTrue("blocking must be live after loading", engine.isBlocking());
        assertNotEquals("a parsed list must block a known ad host",
                FilterEngine.Category.NONE,
                engine.shouldBlock("https://doubleclick.net/pixel.gif"));
    }

    /** Both sections must parse, or one whole category silently does nothing. */
    @Test
    public void bothSectionsParsedIntoTheirOwnCategories() {
        assertEquals(FilterEngine.Category.AD,
                engine.shouldBlock("https://googlesyndication.com/pagead/js/adsbygoogle.js"));
        assertEquals(FilterEngine.Category.TRACKER,
                engine.shouldBlock("https://google-analytics.com/collect"));
    }

    // ------------------------------------------------------------------
    // Requests as a page actually makes them
    // ------------------------------------------------------------------

    /** Subresources are what a tracker arrives as. */
    @Test
    public void realAdRequestsAreBlocked() {
        String[] urls = {
                "https://doubleclick.net/pixel.gif",
                "https://ad.doubleclick.net/ddm/ad",
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                "https://googletagmanager.com/gtm.js?id=GTM-XYZ",
        };
        for (String url : urls) {
            assertNotEquals("must be blocked: " + url,
                    FilterEngine.Category.NONE, engine.shouldBlock(url));
        }
    }

    /**
     * The other half, and the one that decides whether people keep the feature
     * on. A blocker that breaks ordinary sites gets switched off, and an
     * off blocker protects nobody.
     */
    @Test
    public void ordinarySiteRequestsAreNotBlocked() {
        String[] urls = {
                "https://guardian.ng/news/story",
                "https://en.wikipedia.org/wiki/Lagos",
                "https://cdn.jsdelivr.net/npm/jquery/dist/jquery.min.js",
                "https://fonts.gstatic.com/s/roboto/v30/font.woff2",
                "https://github.com/GraphicMiles/Mr-Nobody",
        };
        for (String url : urls) {
            assertEquals("must NOT be blocked: " + url,
                    FilterEngine.Category.NONE, engine.shouldBlock(url));
        }
    }

    /**
     * A host is case-insensitive per RFC 3986.
     *
     * <p>Honest note: this one is a characterisation test, not a guard.
     * Lowercasing happens twice -- in {@code shouldBlock} and again inside
     * {@code Category.matchesHost} -- so removing either still passes. It is
     * kept because it pins the externally visible behaviour, but it would not
     * catch that regression, and pretending otherwise is how a suite comes to
     * look stronger than it is. Verified by deliberately breaking the
     * lowercasing and watching this test stay green.
     */
    @Test
    public void hostMatchingIgnoresCase() {
        assertNotEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://DoubleClick.NET/pixel.gif"));
    }

    /** An explicit port must not defeat the match. */
    @Test
    public void aPortDoesNotDefeatTheMatch() {
        assertNotEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://doubleclick.net:443/pixel.gif"));
    }

    /** Subdomains are the normal serving pattern for ad hosts. */
    @Test
    public void subdomainsOfABlockedHostAreBlocked() {
        assertNotEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://static.ads.doubleclick.net/a.js"));
    }

    /**
     * A lookalike registered domain must not be caught by a suffix test.
     * {@code notdoubleclick.net} ends with the blocked string and is a
     * different site.
     */
    @Test
    public void aLookalikeDomainIsNotBlocked() {
        assertEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://notdoubleclick.net/index.html"));
        assertEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://doubleclick.net.evil.example/x"));
    }

    // ------------------------------------------------------------------
    // Things that must never crash the request path
    // ------------------------------------------------------------------

    /**
     * {@code shouldBlock} runs off the UI thread once per request. A throw
     * here does not merely fail to block -- it breaks page loading.
     */
    @Test
    public void malformedInputIsSurvivedNotThrown() {
        String[] nasty = {
                null, "", "   ", "not a url at all", "http://", "://missing-scheme",
                "https://[bad-ipv6/", "javascript:alert(1)", "chrome://settings",
        };
        for (String url : nasty) {
            assertEquals("must be survivable: " + url,
                    FilterEngine.Category.NONE, engine.shouldBlock(url));
        }
    }

    /** Inline data and about: pages are not network requests. */
    @Test
    public void inlineSchemesAreNeverBlocked() {
        assertEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("data:text/html;base64,PGgxPmhpPC9oMT4="));
        assertEquals(FilterEngine.Category.NONE, engine.shouldBlock("about:blank"));
    }

    // ------------------------------------------------------------------
    // The switch, and the counters the dashboard reports
    // ------------------------------------------------------------------

    /** Off must mean off, or the setting is decoration. */
    @Test
    public void disablingTheEngineStopsAllBlocking() {
        assertNotEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://doubleclick.net/pixel.gif"));

        engine.setEnabled(false);
        assertFalse(engine.isBlocking());
        assertEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://doubleclick.net/pixel.gif"));

        engine.setEnabled(true);
        assertNotEquals(FilterEngine.Category.NONE,
                engine.shouldBlock("https://doubleclick.net/pixel.gif"));
    }

    /**
     * The privacy dashboard's numbers come from these counters. If they do not
     * move, the dashboard reports "0 trackers blocked" while blocking works —
     * which reads to a user exactly like a product that does nothing.
     */
    @Test
    public void countersFollowWhatWasActuallyBlocked() {
        engine.resetPageCounters();
        assertEquals(0, engine.getPageAdsBlocked());
        assertEquals(0, engine.getPageTrackersBlocked());

        engine.shouldBlock("https://doubleclick.net/a.gif");
        engine.shouldBlock("https://googlesyndication.com/b.js");
        engine.shouldBlock("https://google-analytics.com/collect");
        engine.shouldBlock("https://guardian.ng/news");   // not counted

        assertEquals(2, engine.getPageAdsBlocked());
        assertEquals(1, engine.getPageTrackersBlocked());
    }

    /** Per-page counters reset on navigation; lifetime totals do not. */
    @Test
    public void perPageCountersResetButTotalsDoNot() {
        engine.resetPageCounters();
        engine.shouldBlock("https://doubleclick.net/a.gif");
        long totalAfterFirst = engine.getTotalAdsBlocked();
        assertEquals(1, engine.getPageAdsBlocked());

        engine.resetPageCounters();
        assertEquals("a new page starts from zero", 0, engine.getPageAdsBlocked());
        assertEquals("lifetime totals survive navigation",
                totalAfterFirst, engine.getTotalAdsBlocked());
    }

    /** The listener is what pushes live counts to the UI. */
    @Test
    public void theBlockListenerIsToldWhatWasBlocked() {
        final int[] ads = {0};
        final int[] trackers = {0};
        engine.setBlockListener(category -> {
            if (category == FilterEngine.Category.AD) ads[0]++;
            if (category == FilterEngine.Category.TRACKER) trackers[0]++;
        });

        engine.shouldBlock("https://doubleclick.net/a.gif");
        engine.shouldBlock("https://google-analytics.com/collect");
        engine.shouldBlock("https://guardian.ng/news");

        assertEquals(1, ads[0]);
        assertEquals(1, trackers[0]);
    }

    // ------------------------------------------------------------------
    // The two copies of the list must not drift
    // ------------------------------------------------------------------

    /**
     * {@code filters/bundled/blocklist.txt} is the source of truth and
     * {@code src/main/assets/blocklist.txt} is what ships. If they differ, the
     * list being reviewed is not the list being enforced.
     *
     * <p>{@code tools/filter_digest_check.py} also gates this in CI; asserting
     * it here means a developer running tests locally finds out first.
     */
    @Test
    public void theShippedListMatchesTheSourceOfTruth() throws IOException {
        Path mirror = Paths.get("../../../filters/bundled/blocklist.txt");
        if (!Files.isReadable(mirror)) {
            mirror = Paths.get("filters/bundled/blocklist.txt");
        }
        if (!Files.isReadable(mirror)) return;   // not run from a full checkout

        assertEquals("the shipped asset and the source of truth have drifted",
                new String(Files.readAllBytes(blocklistPath()), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(mirror), StandardCharsets.UTF_8));
    }

    /** Unused, but kept honest: the reader used above must see real content. */
    @Test
    public void theBlocklistAssetIsNotEmpty() throws IOException {
        int rules = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Files.newInputStream(blocklistPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("||")) rules++;
            }
        }
        assertTrue("the shipped blocklist must contain rules, found " + rules,
                rules > 50);
    }
}
