package com.mrnobody.agent.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One declared parameter of a tool.
 *
 * <p>Deliberately a small Java object rather than a JSON Schema document. A
 * real JSON Schema validator would drag a JSON library into a 20–45 MB app to
 * check a handful of fields; a spec object validates natively and can still be
 * <em>projected</em> to JSON Schema when a model needs to see it
 * ({@link ToolSpec#toJsonSchema()}).
 */
public final class ParamSpec {

    public enum Type { STRING, INTEGER, BOOLEAN, URL, ENUM }

    private final String name;
    private final Type type;
    private final boolean required;
    private final String description;
    private final List<String> allowed;   // ENUM only
    private final int maxLength;          // STRING/URL only, 0 = unbounded

    private ParamSpec(String name, Type type, boolean required, String description,
                      List<String> allowed, int maxLength) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.description = description == null ? "" : description;
        this.allowed = allowed == null ? Collections.emptyList() : allowed;
        this.maxLength = maxLength;
    }

    public static ParamSpec string(String name, boolean required, String description) {
        return new ParamSpec(name, Type.STRING, required, description, null, 4096);
    }

    public static ParamSpec text(String name, boolean required, String description, int maxLength) {
        return new ParamSpec(name, Type.STRING, required, description, null, maxLength);
    }

    public static ParamSpec url(String name, boolean required, String description) {
        return new ParamSpec(name, Type.URL, required, description, null, 2048);
    }

    public static ParamSpec integer(String name, boolean required, String description) {
        return new ParamSpec(name, Type.INTEGER, required, description, null, 0);
    }

    public static ParamSpec bool(String name, boolean required, String description) {
        return new ParamSpec(name, Type.BOOLEAN, required, description, null, 0);
    }

    public static ParamSpec enumOf(String name, boolean required, String description, String... values) {
        return new ParamSpec(name, Type.ENUM, required, description, Arrays.asList(values), 0);
    }

    public String name() { return name; }
    public Type type() { return type; }
    public boolean required() { return required; }
    public String description() { return description; }
    public List<String> allowed() { return allowed; }

    /**
     * Check one supplied value. Returns null when acceptable, otherwise a
     * message written for whoever supplied it — a model reads these and
     * retries, so they say what was wrong and what was expected.
     */
    public String validate(String raw) {
        boolean missing = raw == null || raw.trim().isEmpty();
        if (missing) {
            return required ? name + " is required" : null;
        }
        String value = raw.trim();
        if (maxLength > 0 && value.length() > maxLength) {
            return name + " is longer than " + maxLength + " characters";
        }
        switch (type) {
            case INTEGER:
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return name + " must be a whole number, got \"" + preview(value) + "\"";
                }
                return null;
            case BOOLEAN:
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    return name + " must be true or false, got \"" + preview(value) + "\"";
                }
                return null;
            case URL:
                String lower = value.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    // Anything else is a scheme we will not hand to a tool:
                    // file:, content:, javascript:, intent: are all ways out of
                    // the sandbox or into another app.
                    return name + " must be an http(s) URL";
                }
                if (value.contains(" ") || value.contains("\n")) {
                    return name + " must not contain whitespace";
                }
                return null;
            case ENUM:
                for (String option : allowed) {
                    if (option.equalsIgnoreCase(value)) return null;
                }
                return name + " must be one of " + String.join(", ", allowed)
                        + ", got \"" + preview(value) + "\"";
            case STRING:
            default:
                return null;
        }
    }

    private static String preview(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
    }

    /** The JSON Schema type name for this parameter. */
    String jsonType() {
        switch (type) {
            case INTEGER: return "integer";
            case BOOLEAN: return "boolean";
            default: return "string";
        }
    }
}
