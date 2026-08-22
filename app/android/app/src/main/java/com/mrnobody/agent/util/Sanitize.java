package com.mrnobody.agent.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw page/JSON text into clean prose before it can be quoted as a
 * citation, or reach a model as evidence.
 *
 * <p>The extraction tools each clean a little: {@link HtmlText} strips tags and
 * a handful of entities, {@link EmbeddedJson} unescapes JSON escapes. Neither
 * is enough on its own. A Next.js page's embedded state carries strings that
 * still {@code &amp;} encode entities and contain literal {@code <div>} /{@code
 * <em>} markup; JSON-LD leaks metadata such as {@code displayType: standard
 * article}; page content carries markdown links {@code [label](url)}; a failed
 * or JS-gated page carries nav chrome ("Skip to navigation", "Oops, something
 * went wrong"). All of it reaches the answer verbatim and reads as garbage.
 *
 * <p>This is the backstop that runs over every source before it is appended to
 * the evidence block — the one place both the deterministic extractive answer
 * and the remote grounded prompt read from. It does not judge content, only
 * strips the transport noise: entities, tags, markdown, metadata and chrome.
 * Conservative on purpose: it removes nothing a normal sentence needs.
 */
public final class Sanitize {

    private Sanitize() {
    }

    // A tag or a fragment of one. Also matches a closing tag and a tag with
    // attributes spread over several lines.
    private static final Pattern TAG = Pattern.compile(
            "(?is)</?[a-zA-Z][^>]*>");
    // Markdown image/link: [label](url) or ![alt](url)
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "(?s)!\\[([^\\]]*)\\]\\(\\s*(?:<[^>]*>)?[^)]*\\)");
    private static final Pattern MARKDOWN_LABEL = Pattern.compile(
            "(?s)\\[([^\\]]*)\\]\\(\\s*(?:<[^>]*>)?[^)]*\\)");
    // A bare parenthesised host, e.g. "(coinmarketcap.com )" left after
    // markdown link stripping or a raw citation reference in body text.
    private static final Pattern PAREN_HOST = Pattern.compile(
            "(?i)\\(\\s*(?:https?://)?[a-z0-9.-]+\\.[a-z]{2,}\\s*\\)");
    // Inline markdown emphasis, code, strikethrough.
    private static final Pattern MD_EMPH = Pattern.compile(
            "(?s)(?:\\*{1,3}|_{1,3}|~~|`+)([^*_~`]+)(?:\\*{1,3}|_{1,3}|~~|`+)");
    // HTML comments and processing instructions.
    private static final Pattern COMMENT = Pattern.compile("(?is)<!--[\\s\\S]*?-->");
    // Numeric entity &#123; or hex &#x1F;.
    private static final Pattern NUM_ENTITY = Pattern.compile(
            "(?i)&#(x?[0-9a-f]+);");

    /** Clean prose for evidence/citation. Never throws; never returns null. */
    public static String prose(String text) {
        if (text == null) return "";
        String s = text;

        // JSON escapes before anything else, so a literal tag or link whose
        // slash is escaped (the common JSON form) becomes strippable.
        s = s.replace("\\/", "/").replace("\\\"", "\"");
        s = COMMENT.matcher(s).replaceAll(" ");
        // Strip real HTML markup first, against its literal < and >. Tags
        // decoded later from &lt; ... &gt; are deliberately preserved as text.
        s = TAG.matcher(s).replaceAll(" ");
        // Markdown links first, so "[label](url)" becomes "label"; then any
        // surviving bare "(host)" reference is dropped.
        s = MARKDOWN_LINK.matcher(s).replaceAll("$1");
        s = MARKDOWN_LABEL.matcher(s).replaceAll("$1");
        // Entities are decoded last; two passes handle a double-encoded
        // &amp;amp; -> &amp; -> &. Reconstructed <c> here is not re-stripped.
        for (int i = 0; i < 2; i++) {
            s = decodeEntities(s);
        }
        s = PAREN_HOST.matcher(s).replaceAll(" ");
        s = MD_EMPH.matcher(s).replaceAll("$1");
        // JSON-LD / schema metadata field names being quoted as prose.
        s = s.replaceAll("(?i)\\b(?:displaytype|@type|@context|articlebody|headline|"
                + "datepublished|datemodified|mainentityofpage|inlanguage|"
                + "articlesection|keywords|author|publisher)\\s*:\\s*", "");
        s = s.replaceAll("[ \\t\\r\\f]+", " ");
        // A tag replacement leaves a space before punctuation, e.g. "... ."
        s = s.replaceAll("\\s+([.,;:!?])", "$1");
        s = s.replaceAll("\\n\\s*\\n+", "\n");
        return s.trim();
    }

    /**
     * Decode numeric and the common named HTML entities. Numeric entities are
     * decoded by code point; named entities map the set a page is likely to
     * use. Anything not matched is left as-is rather than guessed at.
     */
    static String decodeEntities(String s) {
        if (s == null) return "";
        Matcher num = NUM_ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (num.find()) {
            String hex = num.group(1);
            int cp;
            try {
                cp = hex.toLowerCase(Locale.ROOT).startsWith("x")
                        ? Integer.parseInt(hex.substring(1), 16)
                        : Integer.parseInt(hex);
            } catch (NumberFormatException e) {
                continue;
            }
            num.appendReplacement(sb,
                    Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        num.appendTail(sb);
        String out = sb.toString();
        for (String[] e : NAMED) {
            out = out.replace(e[0], e[1]);
        }
        return out;
    }

    // name -> replacement; includes the set HtmlText already handles plus the
    // accented/punctuation ones a page commonly emits.
    private static final String[][] NAMED = {
            {"&nbsp;", " "}, {"&amp;", "&"}, {"&lt;", "<"}, {"&gt;", ">"},
            {"&quot;", "\""}, {"&#39;", "'"}, {"&apos;", "'"},
            {"&ndash;", "-"}, {"&mdash;", "-"}, {"&hellip;", "…"},
            {"&lsquo;", "'"}, {"&rsquo;", "'"}, {"&ldquo;", "\""},
            {"&rdquo;", "\""}, {"&times;", "×"}, {"&divide;", "÷"},
            {"&euro;", "€"}, {"&pound;", "£"}, {"&cent;", "¢"},
            {"&copy;", "©"}, {"&reg;", "®"}, {"&trade;", "™"},
            {"&deg;", "°"}, {"&plusmn;", "±"}, {"&middot;", "·"},
            {"&bull;", "•"}, {"&laquo;", "«"}, {"&raquo;", "»"},
            {"&eacute;", "é"}, {"&egrave;", "è"}, {"&agrave;", "à"},
            {"&ocirc;", "ô"}, {"&uuml;", "ü"}, {"&auml;", "ä"},
            {"&ouml;", "ö"}, {"&ccedil;", "ç"}, {"&ntilde;", "ñ"},
            {"&sect;", "§"}, {"&para;", "¶"}, {"&amp;amp;", "&"},
    };
}
