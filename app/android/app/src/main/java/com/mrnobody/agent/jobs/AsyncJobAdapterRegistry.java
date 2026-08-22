package com.mrnobody.agent.jobs;

import java.util.concurrent.ConcurrentHashMap;

/** Runtime adapter registry; durable jobs store only the stable adapter id. */
public final class AsyncJobAdapterRegistry {
    private final ConcurrentHashMap<String, AsyncJobAdapter> adapters = new ConcurrentHashMap<>();

    public void register(AsyncJobAdapter adapter) {
        if (adapter != null && adapter.id() != null && !adapter.id().isEmpty()) {
            adapters.put(adapter.id(), adapter);
        }
    }
    public void unregister(String id) { if (id != null) adapters.remove(id); }
    public AsyncJobAdapter get(String id) { return id == null ? null : adapters.get(id); }
}
