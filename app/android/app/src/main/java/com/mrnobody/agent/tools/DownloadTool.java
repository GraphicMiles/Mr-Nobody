package com.mrnobody.agent.tools;

import android.content.Context;

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
 * Enqueues a download with the app's own download engine. Validates the URL and
 * never bypasses DRM/paywalls/access controls.
 *
 * <p>Goes through the same engine as a download the user starts by tapping a
 * link, so an agent download lands in the folder they chose, appears in the
 * same list, and can be paused or cancelled the same way. An agent must not
 * have a private back door to the filesystem.
 */
public final class DownloadTool implements Tool {

    private static final ToolSpec SPEC = ToolSpec.named("download")
            .describedAs("Download a file from a URL to the user's download folder.")
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
            String name = com.mrnobody.browser.download.DownloadNaming.fileName(url, null, null);
            com.mrnobody.browser.download.DownloadRecord record =
                    com.mrnobody.browser.download.DownloadEngine.get(context)
                            .enqueue(url, name, null, null, null);
            boolean customFolder =
                    new com.mrnobody.browser.download.DownloadDestination(context).isCustom();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("url", url);
            value.put("id", record.id);
            value.put("name", record.fileName);
            value.put("folder", record.destLabel);
            value.put("customFolder", customFolder);
            return ToolResult.ok(value);
        } catch (Exception e) {
            return ToolResult.fail("download failed: " + e.getMessage());
        }
    }
}
