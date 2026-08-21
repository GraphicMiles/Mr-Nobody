package com.mrnobody.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Pins the native half of the selectable Flutter theme contract. */
public class ThemeWiringTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void nativeSettingsAcceptOnlyClassicOrWarm() throws Exception {
        String settings = read("src/main/java/com/mrnobody/browser/core/Settings.java");

        assertTrue(settings.contains("THEME_CLASSIC = \"classic\""));
        assertTrue(settings.contains("THEME_WARM = \"warm\""));
        assertTrue(settings.contains("THEME_WARM.equalsIgnoreCase(stored) ? THEME_WARM : THEME_CLASSIC"));
        assertTrue(settings.contains("THEME_WARM.equalsIgnoreCase(theme) ? THEME_WARM : THEME_CLASSIC"));
        assertFalse(settings.contains("THEME_SYSTEM"));
        assertFalse(settings.contains("THEME_LIGHT"));
    }

    @Test
    public void platformChannelReadsAndWritesTheTheme() throws Exception {
        String activity = read("src/main/java/com/mrnobody/browser/MainActivity.java");

        assertTrue(activity.contains("m.put(\"theme\", MrNobodyApp.settings().getTheme())"));
        assertTrue(activity.contains("case \"theme\":"));
        assertTrue(activity.contains("MrNobodyApp.settings().setTheme(String.valueOf(value))"));
    }
}
