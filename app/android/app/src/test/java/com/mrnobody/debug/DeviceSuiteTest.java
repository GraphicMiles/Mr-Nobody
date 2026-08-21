package com.mrnobody.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one-tap device suite. The pure checks are executed right here — the
 * JVM runs the same code the phone will — and every check, including the
 * device-only ones, must return a report row rather than throw: an exception
 * mid-suite would eat the whole report.
 */
public class DeviceSuiteTest {

    private static final Set<String> PURE = new HashSet<>(java.util.Arrays.asList(
            "suite.clock", "suite.routing", "suite.skills",
            "suite.scope", "suite.outcome", "suite.junk"));

    @Test
    public void theCatalogueIsOrderedUniqueAndRestoresLast() {
        List<Map<String, Object>> checks = DeviceSuite.describe();
        assertTrue(checks.size() >= 10);
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> c : checks) {
            String id = String.valueOf(c.get("id"));
            assertTrue("duplicate id " + id, ids.add(id));
            assertFalse(String.valueOf(c.get("name")).isEmpty());
        }
        assertEquals("the suite must leave the device as it found it — restore runs last",
                "suite.tor_restore",
                checks.get(checks.size() - 1).get("id"));
        int nobody = indexOf(checks, "suite.tor_nobody");
        int exit = indexOf(checks, "suite.tor_exit");
        assertTrue("the exit check needs Nobody active first", exit > nobody);
    }

    private static int indexOf(List<Map<String, Object>> checks, String id) {
        for (int i = 0; i < checks.size(); i++) {
            if (id.equals(checks.get(i).get("id"))) return i;
        }
        return -1;
    }

    @Test
    public void thePureChecksPassOnTheJvmExactlyAsTheyWillOnThePhone() {
        for (String id : PURE) {
            Map<String, Object> r = DeviceSuite.run(null, id);
            assertEquals(id, r.get("id"));
            assertTrue(id + " failed: " + r.get("detail"),
                    Boolean.TRUE.equals(r.get("pass")));
        }
    }

    @Test
    public void noCheckEverThrowsEvenWithoutAnApp() {
        // Device-only checks on the bare JVM must report, not explode.
        for (Map<String, Object> c : DeviceSuite.describe()) {
            Map<String, Object> r = DeviceSuite.run(null, String.valueOf(c.get("id")));
            assertNotNull(r.get("pass"));
            assertNotNull(r.get("detail"));
        }
        Map<String, Object> unknown = DeviceSuite.run(null, "suite.nonsense");
        assertEquals(Boolean.FALSE, unknown.get("pass"));
    }

    @Test
    public void deviceOnlyChecksSayWhyTheyCannotRunHere() {
        Map<String, Object> speed = DeviceSuite.run(null, "suite.agent_speed");
        assertEquals(Boolean.FALSE, speed.get("pass"));
        assertTrue(String.valueOf(speed.get("detail")).contains("running app"));
    }

    // --------------------------------------------------------------- wiring

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(Paths.get(rel)), StandardCharsets.UTF_8);
    }

    @Test
    public void theChannelAndThePanelCarryTheSuite() throws IOException {
        String activity = read(
                "src/main/java/com/mrnobody/browser/MainActivity.java");
        assertTrue(activity.contains("case \"deviceSuiteChecks\":"));
        assertTrue(activity.contains("case \"deviceSuiteRun\":"));
        assertTrue("suite checks run off the UI thread",
                activity.contains("DeviceSuite.run("));

        String bridge = read("../../lib/bridge/native_bridge.dart");
        assertTrue(bridge.contains("deviceSuiteChecks"));
        assertTrue(bridge.contains("deviceSuiteRun"));

        String panel = read("../../lib/screens/dev_panel_screen.dart");
        assertTrue("one check at a time, progress visible",
                panel.contains("_runSuite"));
        assertTrue("suite results join the copyable report",
                panel.contains("— device suite —"));
        assertTrue("suite failures land in the ⓘ log",
                panel.contains("device suite FAIL"));
    }
}
