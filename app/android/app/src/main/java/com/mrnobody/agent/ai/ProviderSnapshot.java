package com.mrnobody.agent.ai;

import java.util.Objects;

/** Immutable, non-secret AI-provider configuration pinned to one task run. */
public final class ProviderSnapshot {
    public final String id;
    public final String baseUrl;
    public final String modelId;

    public ProviderSnapshot(String id, String baseUrl, String modelId) {
        this.id = clean(id).isEmpty() ? "local" : clean(id);
        this.baseUrl = clean(baseUrl);
        this.modelId = clean(modelId);
    }

    public boolean isLocal() {
        return "local".equals(id);
    }

    public String encode() {
        return escape(id) + "\t" + escape(baseUrl) + "\t" + escape(modelId);
    }

    public static ProviderSnapshot decode(String value) {
        if (value == null || value.isEmpty()) return new ProviderSnapshot("local", "", "");
        String[] parts = value.split("\\t", -1);
        return new ProviderSnapshot(parts.length > 0 ? unescape(parts[0]) : "local",
                parts.length > 1 ? unescape(parts[1]) : "",
                parts.length > 2 ? unescape(parts[2]) : "");
    }

    private static String escape(String value) {
        return clean(value).replace("%", "%25").replace("\t", "%09")
                .replace("\n", "%0A").replace("\r", "%0D");
    }

    private static String unescape(String value) {
        return clean(value).replace("%0D", "\r").replace("%0A", "\n")
                .replace("%09", "\t").replace("%25", "%");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ProviderSnapshot)) return false;
        ProviderSnapshot p = (ProviderSnapshot) other;
        return id.equals(p.id) && baseUrl.equals(p.baseUrl) && modelId.equals(p.modelId);
    }

    @Override public int hashCode() { return Objects.hash(id, baseUrl, modelId); }
}
