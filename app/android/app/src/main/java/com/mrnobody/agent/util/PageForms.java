package com.mrnobody.agent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forms on a page, as something an agent can fill and submit.
 *
 * <p>Download hosts and logins are the same problem: a {@code <form>} with
 * hidden fields and a button. Walking that form — collect fields, pick the
 * one that looks like the action, POST — is what Puppeteer did in the
 * worker in an earlier project. Here it is a pure parse so the headless WebView can
 * submit without a Chromium binary.
 */
public final class PageForms {

    private static final Pattern FORM = Pattern.compile(
            "<form\\b([^>]*)>([\\s\\S]*?)</form>", Pattern.CASE_INSENSITIVE);

    private static final Pattern ATTR = Pattern.compile(
            "\\b(action|method|id|name|class)\\s*=\\s*[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INPUT = Pattern.compile(
            "<input\\b([^>]*)/?>", Pattern.CASE_INSENSITIVE);

    private static final Pattern INPUT_ATTR = Pattern.compile(
            "\\b(type|name|value|id)\\s*=\\s*[\"']([^\"']*)[\"']",
            Pattern.CASE_INSENSITIVE);

    private PageForms() {
    }

    public static final class Form {
        public final String action;
        public final String method;
        public final String id;
        public final Map<String, String> fields;
        public final String hint;

        Form(String action, String method, String id,
             Map<String, String> fields, String hint) {
            this.action = action == null ? "" : action;
            this.method = method == null || method.isEmpty() ? "get" : method;
            this.id = id == null ? "" : id;
            this.fields = fields;
            this.hint = hint == null ? "" : hint;
        }
    }

    public static List<Form> parse(String html, String pageUrl) {
        List<Form> out = new ArrayList<>();
        if (html == null) return out;
        Matcher m = FORM.matcher(html);
        while (m.find() && out.size() < 20) {
            Map<String, String> attrs = attrs(m.group(1), ATTR);
            String action = UrlResolve.resolve(attrs.get("action"), pageUrl);
            if (action == null) action = pageUrl == null ? "" : pageUrl;
            Map<String, String> fields = fieldsOf(m.group(2));
            String hint = (attrs.getOrDefault("id", "") + " "
                    + attrs.getOrDefault("name", "") + " "
                    + attrs.getOrDefault("class", "") + " "
                    + fields.keySet()).toLowerCase(Locale.ROOT);
            out.add(new Form(action, attrs.get("method"), attrs.get("id"), fields, hint));
        }
        return out;
    }

    /**
     * The form most likely to be the one the user wants submitted: a
     * download/create-link form beats a search box beats the first form.
     */
    public static Form pick(List<Form> forms) {
        if (forms == null || forms.isEmpty()) return null;
        for (Form f : forms) {
            if (f.hint.contains("download") || f.hint.contains("op")
                    || f.fields.containsKey("op") || f.fields.containsKey("method_free")) {
                return f;
            }
        }
        for (Form f : forms) {
            if (f.hint.contains("search") || f.fields.containsKey("s")
                    || f.fields.containsKey("q")) {
                return f;
            }
        }
        return forms.get(0);
    }

    private static Map<String, String> fieldsOf(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = INPUT.matcher(body);
        while (m.find()) {
            Map<String, String> a = attrs(m.group(1), INPUT_ATTR);
            String name = a.get("name");
            if (name == null || name.isEmpty()) continue;
            String type = a.getOrDefault("type", "text").toLowerCase(Locale.ROOT);
            if ("submit".equals(type) || "button".equals(type) || "image".equals(type)) {
                if (!fields.containsKey(name) && a.get("value") != null) {
                    fields.put(name, a.get("value"));
                }
                continue;
            }
            if ("checkbox".equals(type) || "radio".equals(type)) continue;
            fields.put(name, a.getOrDefault("value", ""));
        }
        return fields;
    }

    private static Map<String, String> attrs(String tag, Pattern p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tag == null) return out;
        Matcher m = p.matcher(tag);
        while (m.find()) {
            out.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        return out;
    }
}
