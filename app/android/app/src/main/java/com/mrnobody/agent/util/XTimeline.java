package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public X/Twitter HTML → readable markdown.
 *
 * <p>mcp-twikit's {@code convert_tweets_to_markdown} is the shape. We do
 * not log in and we do not call an unofficial API. We lift tweets that
 * the public page already shipped — article bodies, {@code tweetText}
 * nodes, and {@code full_text} in embedded JSON.
 */
public final class XTimeline {

    private static final Pattern TWEET_TEXT = Pattern.compile(
            "data-testid=[\"']tweetText[\"'][^>]*>([\\s\\S]*?)</div>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ARTICLE = Pattern.compile(
            "<article\\b[\\s\\S]*?</article>", Pattern.CASE_INSENSITIVE);

    private static final Pattern FULL_TEXT = Pattern.compile(
            "\"full_text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){8,500})\"");

    private static final Pattern SCREEN = Pattern.compile(
            "\"screen_name\"\\s*:\\s*\"([A-Za-z0-9_]{1,15})\"");

    private static final Pattern HANDLE = Pattern.compile(
            "@([A-Za-z0-9_]{1,15})");

    private XTimeline() {
    }

    public static boolean looksLikeTweets(String html) {
        if (html == null || html.isEmpty()) return false;
        return html.contains("tweetText") || html.contains("\"full_text\"")
                || html.contains("data-testid=\"tweet\"");
    }

    public static String toMarkdown(String html) {
        if (html == null || html.isEmpty()) return "";
        List<Card> cards = new ArrayList<>();
        fromTweetText(html, cards);
        if (cards.isEmpty()) fromArticles(html, cards);
        if (cards.isEmpty()) fromJson(html, cards);
        if (cards.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int n = Math.min(cards.size(), 20);
        for (int i = 0; i < n; i++) {
            Card c = cards.get(i);
            if (out.length() > 0) out.append("\n---\n");
            out.append("### @").append(c.handle).append('\n');
            out.append(c.text);
        }
        return out.toString();
    }

    private static void fromTweetText(String html, List<Card> cards) {
        Matcher m = TWEET_TEXT.matcher(html);
        while (m.find() && cards.size() < 20) {
            String text = HtmlText.toText(m.group(1)).trim();
            if (text.length() < 8) continue;
            cards.add(new Card(nearbyHandle(html, m.start()), text));
        }
    }

    private static void fromArticles(String html, List<Card> cards) {
        Matcher m = ARTICLE.matcher(html);
        while (m.find() && cards.size() < 20) {
            String block = m.group();
            String text = HtmlText.toText(block).trim();
            if (text.length() < 16) continue;
            if (text.length() > 400) text = text.substring(0, 400).trim() + "…";
            cards.add(new Card(firstHandle(block), text));
        }
    }

    private static void fromJson(String html, List<Card> cards) {
        Matcher m = FULL_TEXT.matcher(html);
        while (m.find() && cards.size() < 20) {
            String text = unescape(m.group(1)).trim();
            if (text.length() < 8) continue;
            cards.add(new Card(nearbyJsonHandle(html, m.start()), text));
        }
    }

    private static String nearbyHandle(String html, int at) {
        int from = Math.max(0, at - 800);
        return firstHandle(html.substring(from, at));
    }

    private static String nearbyJsonHandle(String html, int at) {
        int from = Math.max(0, at - 1200);
        Matcher s = SCREEN.matcher(html.substring(from, Math.min(html.length(), at + 200)));
        String last = "tweet";
        while (s.find()) last = s.group(1);
        return last;
    }

    private static String firstHandle(String block) {
        Matcher m = HANDLE.matcher(block);
        return m.find() ? m.group(1) : "tweet";
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/")
                .replace("\\\\", "\\");
    }

    private static final class Card {
        final String handle;
        final String text;

        Card(String handle, String text) {
            this.handle = handle == null || handle.isEmpty() ? "tweet" : handle;
            this.text = text;
        }
    }
}
