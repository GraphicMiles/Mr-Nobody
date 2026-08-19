package com.mrnobody.agent.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class RestrictedToolsTest {

    @Test
    public void fiveToolsAreCatalogued() {
        List<RestrictedTools.Entry> all = RestrictedTools.all();
        assertEquals(5, all.size());
        assertNotNull(RestrictedTools.find(RestrictedTools.TWIKIT));
        assertNotNull(RestrictedTools.find(RestrictedTools.STEALTH_CF));
        assertNotNull(RestrictedTools.find(RestrictedTools.JINA_DEFAULT));
        assertNotNull(RestrictedTools.find(RestrictedTools.SITE_DOWNLOADERS));
        assertNotNull(RestrictedTools.find(RestrictedTools.PHONE_VMS));
    }

    @Test
    public void activeIsHardFalse() {
        assertFalse(RestrictedTools.ACTIVE);
        assertFalse(RestrictedTools.isActive());
        assertFalse(RestrictedTools.isActive(RestrictedTools.TWIKIT));
        assertFalse(RestrictedTools.isActive("anything"));
        for (RestrictedTools.Entry t : RestrictedTools.all()) {
            assertFalse(t.id, t.active);
            assertEquals(t.id, RestrictedTools.Grade.OFF, t.grade);
        }
    }

    @Test
    public void executeRunsAndRefusesBecauseActiveIsFalse() {
        for (RestrictedTools.Entry t : RestrictedTools.all()) {
            Map<String, Object> r = RestrictedTools.execute(t.id);
            assertEquals(t.id, r.get("id"));
            assertEquals(Boolean.TRUE, r.get("ran"));
            assertEquals(Boolean.FALSE, r.get("ok"));
            assertEquals(Boolean.FALSE, r.get("active"));
            assertEquals("off", r.get("grade"));
            String reason = String.valueOf(r.get("reason"));
            assertTrue(reason, reason.contains("active=false"));
        }
    }

    @Test
    public void aFlippedPrefCannotEnableExecute() {
        // There is no setter. Calling execute is the whole surface.
        Map<String, Object> r = RestrictedTools.execute(RestrictedTools.TWIKIT);
        assertEquals(Boolean.FALSE, r.get("active"));
        assertEquals(Boolean.FALSE, r.get("ok"));
    }

    @Test
    public void unknownIdStillExecutesARefusal() {
        Map<String, Object> r = RestrictedTools.execute("not-a-tool");
        assertEquals(Boolean.TRUE, r.get("ran"));
        assertEquals(Boolean.FALSE, r.get("ok"));
    }

    @Test
    public void listShapeIsWhatSettingsReads() {
        List<Map<String, Object>> rows = RestrictedTools.list();
        assertEquals(5, rows.size());
        Map<String, Object> first = rows.get(0);
        assertTrue(first.containsKey("id"));
        assertTrue(first.containsKey("title"));
        assertTrue(first.containsKey("grade"));
        assertEquals(Boolean.FALSE, first.get("active"));
        assertEquals("off", first.get("grade"));
    }
}
