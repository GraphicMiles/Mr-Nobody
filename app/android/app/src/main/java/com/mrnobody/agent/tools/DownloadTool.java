package com.mrnobody.agent.tools;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;

import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;

/**
 * Enqueues a download with the system DownloadManager. Validates the URL and
 * never bypasses DRM/paywalls/access controls.
 */
public final class DownloadTool implements Tool {

    @Override
    public String name() {
        return "download";
    }

    @Override
    public String description() {
        return "Download a file from a URL to the system Downloads directory.";
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String url = request.param("url");
        if (url == null || url.isEmpty()) return ToolResult.fail("download needs 'url'");
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return ToolResult.fail("only http(s) URLs are allowed");
        }
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return ToolResult.fail("DownloadManager unavailable");
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            long id = dm.enqueue(req);
            return ToolResult.ok("Download enqueued (id " + id + ")");
        } catch (Exception e) {
            return ToolResult.fail("download failed: " + e.getMessage());
        }
    }
}
