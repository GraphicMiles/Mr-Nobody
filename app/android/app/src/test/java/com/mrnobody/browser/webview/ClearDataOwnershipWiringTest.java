package com.mrnobody.browser.webview;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression checks for the cross-language Clear Data ownership handoff. */
public class ClearDataOwnershipWiringTest {

    @Test
    public void flutterClosesPrivateOwnersBeforeNativeClearStarts() throws IOException {
        String screen = source("../../lib/screens/clear_data_screen.dart",
                "app/lib/screens/clear_data_screen.dart");
        int closeOwners = screen.indexOf("await widget.onBeforeBrowserDataClear?.call()");
        int clearNative = screen.indexOf("NativeBridge.clearData(selected)");
        assertTrue("private owners must close before ProfileStore deletion starts",
                closeOwners >= 0 && clearNative > closeOwners);

        String main = source("../../lib/main.dart", "app/lib/main.dart");
        assertTrue(main.contains("await _tabs.closePrivateTabs()"));
        assertTrue(main.contains("await WidgetsBinding.instance.endOfFrame"));
    }

    @Test
    public void nativeReleaseCannotSkipWebViewDestroy() throws IOException {
        String source = source("src/main/java/com/mrnobody/browser/webview/TabWebViews.java",
                "app/android/app/src/main/java/com/mrnobody/browser/webview/TabWebViews.java");
        String release = section(source,
                "private static void destroy(@Nullable Page page, boolean managePrivateProfile)",
                "private static boolean hasPrivatePages");
        assertTrue(release.contains("webView.destroy()"));
        assertTrue(release.contains("WebView destroy failed before profile cleanup"));
        assertFalse("teardown must not start a fresh navigation before destroy",
                release.contains("loadUrl(\"about:blank\")"));
    }

    private static String source(String fromModule, String fromRepository) throws IOException {
        Path path = Paths.get(fromModule);
        if (!Files.isReadable(path)) path = Paths.get(fromRepository);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }
}
