package com.mrnobody.agent.tools;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;

import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enqueues a download with the system DownloadManager. Validates the URL and
 * never bypasses DRM/paywalls/access controls.
 */
public final class DownloadTool implements Tool {

    private static final ToolSpec SPEC = ToolSpec.named("download")
            .describedAs("Download a file from a URL to the system Downloads directory.")
            // Writes to the device. Reading a page is not the same as leaving a
            // file on someone's phone.
            .tier(Tier.WRITE)
            .param(ParamSpec.url("url", true, "The http(s) file URL to download."))
            .returns(OutputSpec.of(
                    value -> "Download started: " + value.get("url"), "url", "id"))
            .timeout(10_000)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String url = request.param("url");
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return ToolResult.fail("DownloadManager unavailable");
            String name = com.mrnobody.browser.download.DownloadNaming.fileName(url, null, null);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(name);
            req.setDescription("Downloaded by Mr Nobody");
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            long id = dm.enqueue(req);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("url", url);
            value.put("id", id);
            value.put("name", name);
            return ToolResult.ok(value);
        } catch (Exception e) {
            return ToolResult.fail("download failed: " + e.getMessage());
        }
    }
}
