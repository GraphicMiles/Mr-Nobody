package com.mrnobody.agent.util;

import java.util.Locale;

/** Rejects script/configuration dumps before they become answer evidence. */
public final class ReadableText {

    private ReadableText() {
    }

    /** True when extracted page text contains enough prose to cite. */
    public static boolean usable(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 40) return false;
        String lower = t.substring(0, Math.min(t.length(), 4_000))
                .toLowerCase(Locale.ROOT);
        if (lower.contains("experiment_flags")
                || lower.contains("window.ytplayer")
                || lower.contains("ytcfg.set(")
                || lower.contains("client_canary_state")
                || lower.contains("__next_data__")
                || lower.contains("webpackchunk")) {
            return false;
        }

        int punctuation = 0;
        int letters = 0;
        int braces = 0;
        int escaped = 0;
        int limit = Math.min(t.length(), 4_000);
        for (int i = 0; i < limit; i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c == '{' || c == '}' || c == '[' || c == ']') braces++;
            if (c == '"' || c == ':' || c == ';' || c == '=') punctuation++;
            if (c == '\\' && i + 1 < limit && t.charAt(i + 1) == 'u') escaped++;
        }
        if (letters == 0) return false;
        if (braces > 12 && punctuation > letters / 6) return false;
        if (escaped > 8) return false;
        return true;
    }

    /** True for one code/config-shaped sentence inside an otherwise valid page. */
    public static boolean proseSentence(String sentence) {
        if (sentence == null) return false;
        String s = sentence.trim();
        if (s.length() < 20) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("function()") || lower.contains("window.")
                || lower.contains("experiment_flags") || lower.contains("ytcfg")
                || lower.contains("\\u003") || lower.contains("client_canary_state")) {
            return false;
        }
        if (cssShaped(lower)) return false;
        if (boilerplateSentence(lower)) return false;
        if (keywordDump(lower)) return false;
        if (menuRail(lower)) return false;
        int braces = 0;
        int quotes = 0;
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c == '{' || c == '}' || c == '[' || c == ']') braces++;
            if (c == '"') quotes++;
        }
        return letters > 12 && braces < 6 && quotes < Math.max(8, letters / 8);
    }

    /**
     * A search-result / entity "insight" dump is not prose: it is a run of
     * comma-/ampersand-separated attribute labels with no verb — the exact
     * shape seen as "MrBeast Biography, Age, Girlfriend, Family, Career,
     * Net Worth" and "Net Worth MrBeast Biography, Age, Girlfriend, Family,
     * Career, Net Worth". Quoting it as an answer reads as keyword spam.
     *
     * @param lower the sentence, already lower-cased
     */
    static boolean keywordDump(String lower) {
        // Count the label separators: commas and bare '&'.
        int commas = 0;
        int amps = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == ',') commas++;
            else if (c == '&') amps++;
        }
        int separators = commas + amps;
        if (separators < 3) return false;
        // A proper sentence carries a finite verb. A label list does not.
        if (hasFiniteVerb(lower)) return false;
        return true;
    }

    /**
     * A site menu / navigation rail ("News Sports More News Today's news US
     * Politics World Weather climate change Science Originals … Markets
     * Research") is page furniture, not a sentence. It is a long run of short
     * label tokens with no finite verb.
     *
     * @param lower the sentence, already lower-cased
     */
    static boolean menuRail(String lower) {
        String[] tokens = lower.split("\\s+");
        if (tokens.length < 8) return false;
        // A menu rail is a run of site-section labels. It is identified by
        // vocabulary, not by "short words": count how many tokens are known
        // portal/menu sections and require enough of them plus no finite verb.
        int menuHits = 0;
        for (String t : tokens) {
            String w = t.replaceAll("[^a-z]", "");
            if (MENU_WORDS.contains(w)) menuHits++;
        }
        return menuHits >= 6 && menuHits * 2 >= tokens.length && !hasFiniteVerb(lower);
    }

    private static final java.util.Set<String> MENU_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "news", "sports", "weather", "horoscopes", "shopping",
                    "entertainment", "celebrity", "tv", "movies", "music",
                    "videos", "games", "lifestyle", "health", "parenting",
                    "food", "travel", "autos", "gift", "ideas", "buying",
                    "guides", "finance", "markets", "research", "hotlist",
                    "portfolio", "today", "world", "politics", "business",
                    "tech", "science", "us", "economy", "opinion", "culture",
                    "magazine", "newsletters"));

    /**
     * A broad set of finite English verbs. Used to tell a prose sentence apart
     * from a label/list dump — a keyword dump or menu rail almost never
     * contains a real verb. Kept deliberately large so genuine sentences are
     * not misclassified. Present, past and third-person forms are listed
     * explicitly because stemming is unreliable in a curated classifier.
     */
    private static final java.util.Set<String> FINITE_VERBS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    // be, have, do, modal
                    "is", "are", "was", "were", "am", "be", "been", "being",
                    "has", "have", "had", "having", "does", "do", "did", "doing",
                    "done", "can", "could", "will", "would", "shall", "should",
                    "may", "might", "must", "ought",
                    // common verbs (base / third / past)
                    "say", "says", "said", "state", "states", "stated",
                    "report", "reports", "reported", "write", "writes", "wrote",
                    "show", "shows", "showed", "shown", "contain", "contains",
                    "contained", "include", "includes", "included", "describe",
                    "describes", "described", "define", "defines", "defined",
                    "mean", "means", "meant", "refer", "refers", "referred",
                    "help", "helps", "helped", "make", "makes", "made", "take",
                    "takes", "took", "taken", "give", "gives", "gave", "given",
                    "know", "knows", "knew", "known", "think", "thinks", "thought",
                    "find", "finds", "found", "use", "uses", "used", "work",
                    "works", "worked", "play", "plays", "played", "want", "wants",
                    "wanted", "need", "needs", "needed", "look", "looks", "looked",
                    "start", "starts", "started", "stay", "stays", "stayed",
                    "remain", "remains", "remained", "exist", "exists", "existed",
                    "become", "becomes", "became", "call", "calls", "called",
                    "name", "names", "named", "title", "titles", "titled",
                    "begin", "begins", "began", "begun", "run", "runs", "ran",
                    "lead", "leads", "led", "hold", "holds", "held", "come",
                    "comes", "came", "belong", "belongs", "belonged", "serve",
                    "serves", "served", "sell", "sells", "sold", "buy", "buys",
                    "bought", "grow", "grows", "grew", "grown", "rise", "rises",
                    "rose", "risen", "fall", "falls", "fell", "fallen", "set",
                    "sets", "put", "puts", "pay", "pays", "paid", "earn", "earns",
                    "earned", "live", "lives", "lived", "die", "dies", "died",
                    "born", "create", "creates", "created", "found", "founded",
                    "launch", "launches", "launched", "release", "releases",
                    "released", "publish", "publishes", "published", "offer",
                    "offers", "offered", "provide", "provides", "provided",
                    "allow", "allows", "allowed", "enable", "enables", "enabled",
                    "require", "requires", "required", "support", "supports",
                    "supported", "increase", "increases", "increased", "decrease",
                    "decreases", "decreased", "reach", "reaches", "reached",
                    "hit", "hits", "gain", "gains", "gained", "lose", "loses",
                    "lost", "trade", "trades", "traded", "value", "values",
                    "valued", "estimate", "estimates", "estimated", "report",
                    "claims", "claim", "claimed", "peaked", "average", "averages",
                    "averaged", "cost", "costs", "charge", "charges", "charged",
                    "measured", "measures", "measure", "rank", "ranks", "ranked",
                    "appear", "appears", "appeared", "result", "results",
                    "resulted", "cause", "causes", "caused", "due", "based",
                    "occurs", "occur", "occurred", "develop", "develops",
                    "developed", "produce", "produces", "produced", "generate",
                    "generates", "generated", "explain", "explains", "explained",
                    "refer", "refers", "comprise", "comprises", "comprised",
                    "quality", "consist", "consists", "consisted", "amount",
                    "amounts", "amounted", "total", "totals", "totalled", "count",
                    "counts", "counted", "population", "witness", "witnessed",
                    "feature", "features", "featured", "list", "lists", "listed",
                    "mention", "mentions", "mentioned", "note", "notes", "noted",
                    "said", "add", "adds", "added", "announce", "announces",
                    "announced", "confirm", "confirms", "confirmed", "according",
                    "estimated", "predicted", "projected", "expect", "expects",
                    "expected", "predict", "predicts", "forecast", "forecasts",
                    "forecasted", "surpass", "surpasses", "surpassed", "cross",
                    "crosses", "crossed", "stood", "stand", "stands", "sitting",
                    "sits", "sat"));

    public static boolean hasFiniteVerb(String lower) {
        for (String token : lower.split("[^a-z]+")) {
            if (FINITE_VERBS.contains(token)) return true;
        }
        return false;
    }

    /**
     * Stylesheet text is not prose. A device answer once quoted
     * {@code :root{--i8-background-base-default:#fff;…}} as page evidence —
     * a title glued to a CSS custom-property block forms one giant "sentence"
     * that slips the brace/quote counts (one {@code '{'}, no quotes). CSS has
     * shapes prose never has; any of them disqualifies the sentence.
     *
     * @param lower the sentence, already lower-cased
     */
    static boolean cssShaped(String lower) {
        if (lower.contains(":root{") || lower.contains("{--")
                || lower.contains("@media") || lower.contains("@layer")
                || lower.contains("@import") || lower.contains("!important")
                || lower.contains("@font-face")) {
            return true;
        }
        // A css custom property or declaration run: "--name:value;" chains.
        if (CSS_VAR.matcher(lower).find()) return true;
        // Declaration density: prose does not chain "a:b;c:d;e:f".
        int semis = 0;
        int colons = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == ';') semis++;
            if (c == ':') colons++;
        }
        return semis >= 4 && colons >= 4;
    }

    private static final java.util.regex.Pattern CSS_VAR =
            java.util.regex.Pattern.compile("--[a-z][a-z0-9-]*\\s*:");

    /**
     * Anti-bot walls, consent chrome, reader comments and navigation rails are
     * page furniture, not page content. Quoting them as an answer — "Please
     * enable JavaScript or switch to a supported browser", a site's cookie
     * banner, or a spec's table of contents — was observed on-device and is
     * worse than saying nothing.
     *
     * @param lower the sentence, already lower-cased
     */
    static boolean boilerplateSentence(String lower) {
        if (lower.contains("enable javascript")
                || lower.contains("javascript is disabled")
                || lower.contains("javascript is required")
                || lower.contains("supported browser")
                || lower.contains("browser is out of date")
                || lower.contains("browser is not supported")
                || lower.contains("we use cookies")
                || lower.contains("accept all cookies")
                || lower.contains("cookie preferences")
                || lower.contains("cookie settings")
                || lower.contains("subscribe to our newsletter")
                || lower.contains("sign in to continue")
                || lower.contains("log in to continue")
                || lower.contains("full output was not retained")
                || lower.contains("characters omitted")
                || lower.contains("skip to navigation")
                || lower.contains("skip to main content")
                || lower.contains("skip to content")
                || lower.contains("skip to primary")
                || lower.contains("oops, something went wrong")
                || lower.contains("something went wrong")
                || lower.contains("javascript is currently disabled")
                || lower.contains("for the best experience")
                || lower.contains("menu menu")
                || lower.contains("close menu")
                || lower.contains("subscribe buttons and other page furniture")
                || lower.contains("page furniture follows")
                || lower.contains("subscribe to our")
                || lower.contains("other page furniture follow")) {
            return true;
        }
        // Navigation/table-of-contents runs: "Table of contents 1 Introduction
        // 2 Common infrastructure 3 Semantics …" — many bare-number tokens in
        // one "sentence" is a link rail, not prose.
        String[] tokens = lower.split("\\s+");
        if (tokens.length >= 8) {
            int numeric = 0;
            for (String token : tokens) {
                if (token.matches("\\d{1,3}")) numeric++;
            }
            if (numeric >= 4 && numeric * 4 >= tokens.length) return true;
        }
        return lower.contains("table of contents");
    }
}
