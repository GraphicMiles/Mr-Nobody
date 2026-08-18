package com.mrnobody.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Phase 1 benchmark's pure half. Every check in {@code runPure()} must
 * pass on the JVM — this is what guarantees the panel reports real failures on
 * a device rather than failing because a check itself is broken. Each result is
 * asserted by id, and a failure here carries the detail line that would appear
 * in the panel.
 */
public class DiagnosticsTest {

    private static Map<String, Diagnostics.Result> byId(List<Diagnostics.Result> results) {
        Map<String, Diagnostics.Result> m = new HashMap<>();
        for (Diagnostics.Result r : results) m.put(r.id, r);
        return m;
    }

    private static void assertPass(Diagnostics.Result r) {
        assertTrue(r.id + " should pass: " + r.detail, r.pass);
    }

    @Test
    public void everyPureCheckPasses() {
        List<Diagnostics.Result> results = Diagnostics.runPure();
        Map<String, Diagnostics.Result> byId = byId(results);

        // All ten pure checks are present and green.
        assertEquals(10, results.size());
        for (Diagnostics.Result r : results) {
            assertPass(r);
        }

        // And each one is identifiable, so a device failure is reportable.
        for (String id : new String[]{
                "input.route", "search.parse", "hosts.detect", "planner.plan",
                "terminal.gate", "workspace.sandbox", "identity.sign", "network.route",
                "datasaver.policy", "memory.rank"}) {
            assertTrue("missing check " + id, byId.containsKey(id));
        }
    }

    @Test
    public void resultsSerialiseToTheChannelShape() {
        Diagnostics.Result r = Diagnostics.runPure().get(0);
        Map<String, Object> m = r.toMap();
        assertEquals(r.id, m.get("id"));
        assertEquals(r.name, m.get("name"));
        assertEquals(r.pass, m.get("pass"));
        assertEquals(r.detail, m.get("detail"));
    }
}
