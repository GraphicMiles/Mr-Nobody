package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.browser.download.DownloadDestination;
import com.mrnobody.browser.download.DownloadEngine;
import com.mrnobody.browser.download.DownloadNaming;
import com.mrnobody.browser.download.DownloadRecord;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enqueues a download with the app's own download engine and waits until the
 * transfer is actually finished.
 *
 * <p>Goes through the same engine as a download the user starts by tapping a
 * link, so an agent download lands in the folder they chose, appears in the
 * same list, and can be paused or cancelled the same way. An agent must not
 * have a private back door to the filesystem.
 *
 * <p>"Downloaded" is only reported after {@link DownloadRecord.Status#COMPLETED}.
 * Enqueue-and-return was a lie: the UI said the file was there while bytes
 * were still moving, or after the transfer had already failed.
 */
public final class DownloadTool implements Tool {

    /** How long we will sit on a single transfer before reporting "still going". */
    static final long WAIT_MS = 180_000L;

    private static final ToolSpec SPEC = ToolSpec.named("download")
            .describedAs("Download a file from a URL to the user's download folder.")
            // Sandbox write: a new file in the app's download folder. Not a
            // click on a live page, not an overwrite of the user's documents.
            .tier(Tier.SANDBOX)
            .param(ParamSpec.url("url", true, "The http(s) file URL to download."))
            .returns(OutputSpec.of(DownloadTool::render, "url", "id", "status"))
            .timeout(WAIT_MS + 15_000L)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String url = request.param("url");
        try {
            String name = DownloadNaming.fileName(url, null, null);
            DownloadEngine engine = DownloadEngine.get(context);
            DownloadRecord record = engine.enqueue(url, name, null, null, null);
            DownloadRecord done = engine.awaitTerminal(record.id, WAIT_MS);
            if (done == null) done = record;

            boolean customFolder = new DownloadDestination(context).isCustom();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("url", url);
            value.put("id", done.id);
            value.put("name", done.fileName);
            value.put("folder", done.destLabel);
            value.put("customFolder", customFolder);
            value.put("status", done.status == null ? "UNKNOWN" : done.status.name());
            value.put("bytes", done.bytes);
            value.put("total", done.total);
            if (done.error != null && !done.error.isEmpty()) {
                value.put("error", done.error);
            }

            if (done.status == DownloadRecord.Status.FAILED
                    || done.status == DownloadRecord.Status.CANCELLED) {
                String why = done.error == null || done.error.isEmpty()
                        ? done.status.name() : done.error;
                return ToolResult.fail("download failed: " + why);
            }
            return ToolResult.ok(value);
        } catch (Exception e) {
            return ToolResult.fail("download failed: " + e.getMessage());
        }
    }

    private static String render(Map<String, Object> value) {
        String status = String.valueOf(value.get("status"));
        String name = String.valueOf(value.get("name"));
        String folder = String.valueOf(value.get("folder"));
        if ("COMPLETED".equals(status)) {
            return "Downloaded " + name + " to " + folder + ".";
        }
        if ("RUNNING".equals(status) || "QUEUED".equals(status)) {
            return "Download still in progress: " + name + " (not finished yet).";
        }
        return "Download " + status.toLowerCase() + ": " + name;
    }
}
