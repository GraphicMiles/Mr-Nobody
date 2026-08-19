package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSS/Atom candidates for a host, and a readable view of a feed body.
 *
 * <p>Agent-Reach treats a feed as the zero-config monitor. A recurring
 * check should hit {@code /feed} before it scrapes the homepage again.
 */
public final class FeedDiscover {

    private static final Pattern ITEM = Pattern.compile(
            "<(?:item|entry)\\b[\\s\\S]*?</(?:item|entry)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_HREF = Pattern.compile(
            "<link[^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TEXT = Pattern.compile(
            "<link[^>]*>([^<]+)</link>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT = Pattern.compile(
            "<link[^>]+rel=[\"']alternate[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile(
            "href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE = Pattern.compile(
            "type=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private FeedDiscover() {
    }

    /** Conventional feed URLs on {@code host}, plus any {@code rel=alternate} in {@code html}. */
    public static List<String> candidates(String host, String html) {
        List<String> out = new ArrayList<>();
        if (host != null && !host.isEmpty()) {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) h = h.substring(4);
            out.add("https://" + h + "/feed");
            out.add("https://" + h + "/rss");
            out.add("https://" + h + "/feed.xml");
            out.add("https://" + h + "/atom.xml");
            out.add("https://" + h + "/index.xml");
        }
        if (html != null) {
            Matcher alt = ALT.matcher(html);
            while (alt.find() && out.size() < 12) {
                String tag = alt.group();
                Matcher t = TYPE.matcher(tag);
                String type = t.find() ? t.group(1).toLowerCase(Locale.ROOT) : "";
                if (!type.contains("rss") && !type.contains("atom") && !type.contains("xml")) {
                    continue;
                }
                Matcher href = HREF.matcher(tag);
                if (!href.find()) continue;
                String abs = UrlResolve.resolve(href.group(1),
                        host == null ? null : "https://" + host + "/");
                if (abs != null && !out.contains(abs)) out.add(abs);
            }
        }
        return out;
    }

    /** Titles and links from an RSS/Atom body. */
    public static String toText(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        Matcher m = ITEM.matcher(xml);
        int n = 0;
        while (m.find() && n < 20) {
            String item = m.group();
            String title = first(TITLE, item);
            String link = first(LINK_HREF, item);
            if (link.isEmpty()) link = first(LINK_TEXT, item);
            if (title.isEmpty() && link.isEmpty()) continue;
            n++;
            if (out.length() > 0) out.append('\n');
            out.append(n).append(". ").append(stripCdata(title));
            if (!link.isEmpty()) out.append(" — ").append(link.trim());
        }
        return out.toString();
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String stripCdata(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) {
            t = t.substring(9, t.length() - 3);
        }
        return HtmlText.toText(t);
    }
}
