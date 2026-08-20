package com.mrnobody.browser.blocking;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression checks for the thin Android seam around the pure-Java policies. */
public class WebViewBlockingWiringTest {

    @Test
    public void mainFrameDoesNotBypassTheFilterAgain() throws IOException {
        String method = section(webViewSource(),
                "public WebResourceResponse shouldInterceptRequest",
                "public boolean shouldOverrideUrlLoading");
        assertTrue(method.contains("boolean mainFrame = request.isForMainFrame()"));
        assertTrue(method.contains("NavigationGuard.evaluate("));
        assertTrue(method.contains("navigationSource()"));
        assertTrue(method.contains("lastMainFrameRequestUrl = url"));
        assertTrue(method.contains("filters().shouldBlock(url)"));
        assertTrue(method.contains("reportBlocked(category, mainFrame)"));
        assertTrue(method.contains("publishRestoredSource(sourceUrl)"));
        assertTrue("a blocked fallback must leave the source document in place",
                method.contains("BLOCKED_MIME, \"utf-8\", 204"));
        assertFalse("the old defect returned before classifying every main frame",
                method.matches("(?s).*isForMainFrame\\(\\).*?return null;.*?shouldBlock\\(url\\).*"));
    }

    @Test
    public void topLevelNavigationUsesTheSharedNavigationPolicy() throws IOException {
        String method = section(webViewSource(),
                "public boolean shouldOverrideUrlLoading",
                "public void onPageStarted");
        assertTrue(method.contains("NavigationGuard.evaluate("));
        assertTrue(method.contains("reportBlocked(category, true)"));
        assertTrue(method.contains("return true"));
    }

    @Test
    public void historyChangesPublishTheRestoredUrl() throws IOException {
        String method = section(webViewSource(),
                "public void doUpdateVisitedHistory",
                "public void onPageFinished");
        assertTrue(method.contains("lastCommittedUrl = url"));
        assertTrue(method.contains("data.put(\"url\", url)"));
        assertTrue(method.contains("send(\"onNavigation\", data)"));
    }

    @Test
    public void popupSurfaceIsSuppressedButFocusedLinksCanStayInTab() throws IOException {
        String source = webViewSource();
        assertTrue(source.contains("setSupportMultipleWindows(true)"));
        String method = section(source, "public boolean onCreateWindow", "onShowFileChooser");
        assertTrue(method.contains("requestFocusNodeHref"));
        assertTrue(method.contains("handlePopupTarget"));
        assertTrue(method.contains("return false"));
    }

    private static String webViewSource() throws IOException {
        Path source = Paths.get("src/main/java/com/mrnobody/browser/webview/MrNobodyWebView.java");
        if (!Files.isReadable(source)) {
            source = Paths.get("app/android/app/src/main/java/com/mrnobody/browser/webview/MrNobodyWebView.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }
}
