package com.mrnobody.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything a tool declares about itself: identity, what it is allowed to do,
 * the parameters it accepts, and the shape of what it returns.
 *
 * <p>{@link #toJsonSchema()} is an explicit allowlist — name, description and
 * parameters, nothing else. Timeouts, tiers and renderers are ours; a model has
 * no business seeing them, and a leak would be a prompt-injection surface as
 * well as wasted context.
 */
public final class ToolSpec {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final String name;
    private final String description;
    private final Tier tier;
    private final List<ParamSpec> params;
    private final OutputSpec output;
    private final long timeoutMs;

    private ToolSpec(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.tier = b.tier;
        this.params = Collections.unmodifiableList(new ArrayList<>(b.params));
        this.output = b.output;
        this.timeoutMs = b.timeoutMs;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public String name() { return name; }
    public String description() { return description; }
    public Tier tier() { return tier; }
    public List<ParamSpec> params() { return params; }
    public OutputSpec output() { return output; }
    public long timeoutMs() { return timeoutMs; }

    /**
     * Validate a request's parameters. Returns null when acceptable, otherwise
     * every problem at once — a model that gets one error at a time burns a
     * step per mistake.
     */
    public String validate(ToolRequest request) {
        List<String> problems = new ArrayList<>();
        for (ParamSpec param : params) {
            String problem = param.validate(request.param(param.name()));
            if (problem != null) problems.add(problem);
        }
        for (Map.Entry<String, String> supplied : request.params().entrySet()) {
            if (!declares(supplied.getKey())) {
                problems.add("unknown parameter \"" + supplied.getKey() + "\"");
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    private boolean declares(String name) {
        for (ParamSpec param : params) {
            if (param.name().equals(name)) return true;
        }
        return false;
    }

    /** The model-facing projection. Nothing internal crosses this boundary. */
    public String toJsonSchema() {
        StringBuilder properties = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (ParamSpec param : params) {
            if (properties.length() > 0) properties.append(',');
            properties.append('"').append(escape(param.name())).append("\":{")
                    .append("\"type\":\"").append(param.jsonType()).append('"');
            if (!param.description().isEmpty()) {
                properties.append(",\"description\":\"").append(escape(param.description())).append('"');
            }
            if (!param.allowed().isEmpty()) {
                properties.append(",\"enum\":[");
                for (int i = 0; i < param.allowed().size(); i++) {
                    if (i > 0) properties.append(',');
                    properties.append('"').append(escape(param.allowed().get(i))).append('"');
                }
                properties.append(']');
            }
            properties.append('}');
            if (param.required()) {
                if (required.length() > 0) required.append(',');
                required.append('"').append(escape(param.name())).append('"');
            }
        }
        return "{\"name\":\"" + escape(name) + "\","
                + "\"description\":\"" + escape(description) + "\","
                + "\"parameters\":{\"type\":\"object\","
                + "\"properties\":{" + properties + "},"
                + "\"required\":[" + required + "]}}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    public static final class Builder {
        private final String name;
        private String description = "";
        private Tier tier = Tier.READ;
        private final List<ParamSpec> params = new ArrayList<>();
        private OutputSpec output = OutputSpec.of(OutputSpec::describe);
        private long timeoutMs = DEFAULT_TIMEOUT_MS;

        private Builder(String name) {
            this.name = name;
        }

        public Builder describedAs(String description) {
            this.description = description;
            return this;
        }

        public Builder tier(Tier tier) {
            this.tier = tier;
            return this;
        }

        public Builder param(ParamSpec spec) {
            params.add(spec);
            return this;
        }

        public Builder returns(OutputSpec output) {
            this.output = output;
            return this;
        }

        public Builder timeout(long millis) {
            this.timeoutMs = millis;
            return this;
        }

        public ToolSpec build() {
            return new ToolSpec(this);
        }
    }
}
