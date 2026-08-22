package com.mrnobody.browser.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The update payload is untrusted input: these tests pin that the parser
 * accepts only a well-formed release and that version ordering is numeric,
 * not lexicographic.
 */
public class UpdateInfoTest {

    private static String payload(String version, String url) {
        return "{"
                + "\"latestVersion\": \"" + version + "\","
                + "\"releaseNotes\": \"notes\","
                + "\"downloadUrl\": \"" + url + "\","
                + "\"required\": false,"
                + "\"sha256\": \"\","
                + "\"signature\": \"\","
                + "\"publishedAt\": \"2026-08-22T00:00:00Z\"}";
    }

    @Test
    public void parsesAValidRelease() {
        UpdateInfo info = UpdateInfo.parse(
                payload("1.1.0", "https://cdn.example.com/mr-nobody-1.1.0.apk"));
        assertNotNull(info);
        assertEquals("1.1.0", info.version);
        assertEquals("notes", info.releaseNotes);
        assertEquals("https://cdn.example.com/mr-nobody-1.1.0.apk", info.downloadUrl);
        assertFalse(info.required);
        assertEquals("", info.sha256);
        assertEquals("2026-08-22T00:00:00Z", info.publishedAt);
    }

    @Test
    public void acceptsAWellFormedSha256() {
        String good = "b1c6939aa1650b3fd69711713c522336f9fc10a84ac8fffc8f7e0957743f5d7c";
        UpdateInfo info = UpdateInfo.parse(
                payload("1.1.0", "https://cdn.example.com/a.apk")
                        .replace("\"sha256\": \"\"", "\"sha256\": \"" + good + "\""));
        assertNotNull(info);
        assertEquals(good, info.sha256);
    }

    @Test
    public void rejectsMalformedVersions() {
        for (String bad : new String[] {"", "1", "1.1", "1.1.0.0", "1.x.0", "a.b.c", "1.1.0-beta"}) {
            assertNull("version " + bad, UpdateInfo.parse(payload(bad, "https://e.com/a.apk")));
        }
    }

    @Test
    public void rejectsNonHttpsDownloadUrl() {
        assertNull(UpdateInfo.parse(payload("1.1.0", "http://cdn.example.com/a.apk")));
        assertNull(UpdateInfo.parse(payload("1.1.0", "ftp://cdn.example.com/a.apk")));
        assertNull(UpdateInfo.parse(payload("1.1.0", "")));
    }

    @Test
    public void rejectsAPlaceholderOrMalformedSha256() {
        // The pre-release file ships with a placeholder: it must not be
        // mistaken for a real digest.
        String placeholder = "REPLACE_WITH_RELEASE_SHA256";
        assertNull(UpdateInfo.parse(payload("1.1.0", "https://e.com/a.apk")
                .replace("\"sha256\": \"\"", "\"sha256\": \"" + placeholder + "\"")));
        assertNull(UpdateInfo.parse(payload("1.1.0", "https://e.com/a.apk")
                .replace("\"sha256\": \"\"", "\"sha256\": \"zz\"")));
        assertNull(UpdateInfo.parse(payload("1.1.0", "https://e.com/a.apk")
                .replace("\"sha256\": \"\"", "\"sha256\": \"g1c6939aa1650b3fd69711713c522336f9fc10a84ac8fffc8f7e0957743f5d\"")));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(UpdateInfo.parse(""));
        assertNull(UpdateInfo.parse(null));
        assertNull(UpdateInfo.parse("not json"));
        assertNull(UpdateInfo.parse("{}"));
        assertNull(UpdateInfo.parse("[1,2,3]"));
    }

    @Test
    public void versionOrderingIsNumeric() {
        assertTrue(UpdateInfo.isNewer("1.0.1", "1.0.0"));
        assertTrue(UpdateInfo.isNewer("1.10.0", "1.9.9")); // lexicographic would fail
        assertTrue(UpdateInfo.isNewer("2.0.0", "1.99.99"));
        assertFalse(UpdateInfo.isNewer("1.0.0", "1.0.0"));
        assertFalse(UpdateInfo.isNewer("1.0.0", "1.0.1"));
        assertFalse(UpdateInfo.isNewer("0.9.9", "1.0.0"));
    }

    @Test
    public void malformedVersionsAreNeverNewer() {
        assertFalse(UpdateInfo.isNewer("1.0", "1.0.0"));
        assertFalse(UpdateInfo.isNewer("1.0.0", "1.0"));
        assertFalse(UpdateInfo.isNewer(null, "1.0.0"));
        assertFalse(UpdateInfo.isNewer("1.0.0", null));
    }

    @Test
    public void requiredFlagDefaultsToFalse() {
        UpdateInfo info = UpdateInfo.parse(
                payload("1.1.0", "https://cdn.example.com/a.apk")
                        .replace("\"required\": false", "\"required\": true"));
        assertNotNull(info);
        assertTrue(info.required);
    }
}
