package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.browser.core.Settings;

import java.net.URLEncoder;

/**
 * Runs a web search by delegating to the configured privacy-respecting provider
 * (DuckDuckGo default). V1 returns the search URL + the query; V2 parses results.
 * The query goes directly to the provider — never proxied through us.
 */
public final class SearchTool implements Tool {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Search the web for a query.";
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        String query = request.param("q");
        if (query == null || query.trim().isEmpty()) {
            return ToolResult.fail("search requires a 'q' parameter");
        }
        String engine = context != null
                ? com.mrnobody.browser.MrNobodyApp.settings().getSearchEngine()
                : Settings.SEARCH_DDG;
        String url = engine + URLEncoder.encode(query.trim());
        return ToolResult.ok("Search: " + query.trim() + "\n" + url);
    }
}
