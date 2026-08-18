package com.mrnobody.agent.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The search engines the agent will try, and how to read each one's results
 * out of a rendered page.
 *
 * <p>Two things make this work where a regex over fetched HTML does not.
 * First, the query runs in the headless WebView — JavaScript executes, cookies
 * exist, the request looks like a browser — so a provider is far less likely to
 * answer with a challenge page. Second, results are read with DOM selectors
 * against the rendered document instead of patterns against markup, so a
 * layout change degrades the extraction rather than silently emptying it.
 *
 * <p>A provider that answers with nothing is skipped and the next is tried. If
 * every provider refuses, the agent says so — it never proceeds to an answer
 * with no sources.
 */
public final class SearchProviders {

    /** How many results are worth having before we stop asking other engines. */
    public static final int ENOUGH = 3;

    public static final class Provider {
        public final String id;
        public final String name;
        private final String queryTemplate;
        private final String selectors;

        Provider(String id, String name, String queryTemplate, String selectors) {
            this.id = id;
            this.name = name;
            this.queryTemplate = queryTemplate;
            this.selectors = selectors;
        }

        public String url(String query) {
            return queryTemplate.replace("{q}", encode(query));
        }

        /** The script that turns this provider's rendered page into JSON. */
        public String script(int max) {
            return EXTRACTOR.replace("/*SELECTORS*/", selectors).replace("/*MAX*/", String.valueOf(max));
        }
    }

    // Selector sets, most specific first. `block` is one result; `link` is the
    // anchor to follow; `title` and `snippet` are read inside the block.
    private static final Provider DDG_HTML = new Provider(
            "ddg", "DuckDuckGo",
            "https://html.duckduckgo.com/html/?q={q}",
            "{block:'.result,.web-result',link:'a.result__a',title:'a.result__a',snippet:'.result__snippet'}");

    private static final Provider DDG_LITE = new Provider(
            "ddg-lite", "DuckDuckGo Lite",
            "https://lite.duckduckgo.com/lite/?q={q}",
            "{block:'tr',link:'a.result-link',title:'a.result-link',snippet:'.result-snippet'}");

    private static final Provider BING = new Provider(
            "bing", "Bing",
            "https://www.bing.com/search?q={q}&format=rss&count=10".replace("&format=rss&count=10", ""),
            "{block:'li.b_algo',link:'h2 a',title:'h2',snippet:'.b_caption p,.b_lineclamp2,.b_algoSlug'}");

    private static final Provider STARTPAGE = new Provider(
            "startpage", "Startpage",
            "https://www.startpage.com/sp/search?query={q}",
            "{block:'.w-gl__result,.result',link:'a.w-gl__result-title,a.result-link',"
                    + "title:'.w-gl__result-title,h3',snippet:'.w-gl__description,.description'}");

    private static final Provider MOJEEK = new Provider(
            "mojeek", "Mojeek",
            "https://www.mojeek.com/search?q={q}",
            "{block:'ul.results-standard li,li.result',link:'a.title,h2 a',title:'a.title,h2',snippet:'p.s,.s'}");

    private static final Provider GOOGLE = new Provider(
            "google", "Google",
            "https://www.google.com/search?q={q}",
            "{block:'div.g,div[data-sokoban-container],div.MjjYud',link:'a[href^=\"http\"]',"
                    + "title:'h3',snippet:'div[data-sncf], .VwiC3b, .IsZvec'}");

    private SearchProviders() {
    }

    /**
     * The order to try, with the user's configured engine first.
     *
     * <p>Google is only ever used when the user has explicitly selected it:
     * automated querying is against its terms, and it is the most likely to
     * answer a non-browser-looking request with a consent wall anyway.
     */
    public static List<Provider> chain(String preferredEngineUrl) {
        String preferred = idFor(preferredEngineUrl);
        List<Provider> chain = new ArrayList<>();
        for (Provider p : Arrays.asList(DDG_HTML, DDG_LITE, BING, MOJEEK, STARTPAGE)) {
            if (p.id.equals(preferred)) chain.add(0, p);
            else chain.add(p);
        }
        if ("google".equals(preferred)) chain.add(0, GOOGLE);
        return chain;
    }

    /** Map the stored search-engine URL prefix onto a provider id. */
    static String idFor(String engineUrl) {
        if (engineUrl == null) return "ddg";
        String u = engineUrl.toLowerCase(Locale.ROOT);
        if (u.contains("bing.")) return "bing";
        if (u.contains("startpage.")) return "startpage";
        if (u.contains("mojeek.")) return "mojeek";
        if (u.contains("google.")) return "google";
        return "ddg";
    }

    public static String encode(String query) {
        try {
            return URLEncoder.encode(query == null ? "" : query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return query == null ? "" : query.replace(' ', '+');
        }
    }

    /**
     * Reads results out of whatever page is loaded.
     *
     * <p>Tries the provider's own selectors, then falls back to a heuristic
     * that works on almost any results page: every heading inside a link.
     * Returns JSON so the Java side does no HTML parsing at all.
     */
    private static final String EXTRACTOR =
            "(function(){try{" +
            "var cfg=/*SELECTORS*/;var max=/*MAX*/;" +
            "function txt(el){return el?String(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim():'';}" +
            "function host(u){try{return new URL(u).host.replace(/^www\\./,'');}catch(e){return '';}}" +
            "var engineHosts=/(^|\\.)((duckduckgo|bing|google|mojeek|startpage|yahoo|msn)\\.[a-z.]+)$/i;" +
            "var out=[],seen={};" +
            "function push(title,url,snippet){" +
            " if(!title||!url||!/^https?:/i.test(url))return;" +
            " var h=host(url); if(!h||engineHosts.test(h))return;" +
            " var key=h+'|'+title.slice(0,60); if(seen[key])return; seen[key]=1;" +
            " out.push({title:title.slice(0,300),url:url,snippet:String(snippet||'').slice(0,500)});}" +
            "var blocks=document.querySelectorAll(cfg.block);" +
            "for(var i=0;i<blocks.length&&out.length<max;i++){var b=blocks[i];" +
            " var a=b.querySelector(cfg.link); if(!a)continue;" +
            " push(txt(b.querySelector(cfg.title))||txt(a),a.href,txt(b.querySelector(cfg.snippet)));}" +
            "if(out.length===0){" +
            " var heads=document.querySelectorAll('a h3, a h2, h3 a, h2 a');" +
            " for(var j=0;j<heads.length&&out.length<max;j++){" +
            "  var el=heads[j];var link=el.closest('a')||el.querySelector('a');if(!link)continue;" +
            "  var block=link.closest('li,div,article')||link.parentElement;" +
            "  var snip=block?txt(block).replace(txt(el),'').trim():'';" +
            "  push(txt(el),link.href,snip);}}" +
            "return JSON.stringify(out);" +
            "}catch(e){return JSON.stringify([]);}})()";
}
