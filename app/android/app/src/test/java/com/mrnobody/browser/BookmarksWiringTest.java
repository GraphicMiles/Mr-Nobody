package com.mrnobody.browser;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Bookmarks outside the tab menu. Device-reported (2026-08-21): bookmarks
 * were only reachable from inside an open tab's sheet, capped at eight, with
 * no way to delete — a user on the Settings page could not see what they had
 * saved at all. This pins the full path: channel → bridge → screen → row.
 */
public class BookmarksWiringTest {

    private static String read(String rel) throws IOException {
        return new String(Files.readAllBytes(Paths.get(rel)), StandardCharsets.UTF_8);
    }

    @Test
    public void theChannelExposesRemoveAsWellAsListAndAdd() throws IOException {
        String activity = read("src/main/java/com/mrnobody/browser/MainActivity.java");
        assertTrue(activity.contains("case \"bookmarks\":"));
        assertTrue(activity.contains("case \"addBookmark\":"));
        assertTrue("the store's remove(id) was never reachable from the UI",
                activity.contains("case \"removeBookmark\":"));
        assertTrue(activity.contains("MrNobodyApp.bookmarks().remove(id.longValue())"));
    }

    @Test
    public void theBridgeAndScreenCarryTheFullList() throws IOException {
        String bridge = read("../../lib/bridge/native_bridge.dart");
        assertTrue(bridge.contains("removeBookmark"));

        String screen = read("../../lib/screens/bookmarks_screen.dart");
        assertTrue("the screen lists every bookmark, not a capped eight",
                screen.contains("for (final m in _marks)"));
        assertTrue("each row can be deleted", screen.contains("removeBookmark"));
        assertTrue("tapping opens in a tab via the shell's opener",
                screen.contains("onOpenUrl"));
        assertTrue("an empty list explains where bookmarks come from",
                screen.contains("Bookmark this page"));
    }

    @Test
    public void settingsReachesBookmarksFromBothShellPaths() throws IOException {
        String settings = read("../../lib/screens/settings_screen.dart");
        assertTrue(settings.contains("label: 'Bookmarks'"));
        assertTrue("the row shows a live count like Downloads does",
                settings.contains("_bookmarkCount"));
        assertTrue(settings.contains("BookmarksScreen(onOpenUrl: widget.onOpenUrl)"));

        String main = read("../../lib/main.dart");
        int wired = main.split("onOpenUrl: _openUrlFromTask", -1).length - 1;
        assertTrue("both SettingsScreen call sites hand over the URL opener; found "
                + wired, wired >= 3); // task chat + browser-destination + shell settings
    }
}
