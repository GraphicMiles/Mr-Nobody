package com.mrnobody.browser.blocking;

/**
 * One policy entry point for a top-level WebView navigation.
 *
 * <p>Keeping this Android-free makes the main-frame decision testable on the
 * JVM. Subresources deliberately bypass this class and continue through
 * {@link FilterEngine#shouldBlock(String)} in shouldInterceptRequest.</p>
 */
public final class NavigationGuard {
    private NavigationGuard() {}

    /**
     * Classify a requested main document and update the filter counters once
     * when it is refused. Returns {@link FilterEngine.Category#NONE} for an
     * allowed request or anything that is not a main frame.
     */
    public static FilterEngine.Category evaluate(FilterEngine filters,
                                                  String sourceUrl,
                                                  String targetUrl,
                                                  boolean mainFrame) {
        if (filters == null || !mainFrame) return FilterEngine.Category.NONE;

        FilterEngine.Category listed = filters.shouldBlock(targetUrl);
        if (listed != FilterEngine.Category.NONE) return listed;

        if (filters.isBlocking()
                && RedirectGuard.shouldBlock(sourceUrl, targetUrl, true)) {
            filters.recordBlocked(FilterEngine.Category.AD);
            return FilterEngine.Category.AD;
        }
        return FilterEngine.Category.NONE;
    }
}
