package com.mrnobody.browser.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the list of tabs and the notion of an active tab.
 *
 * Tab order == navigation order. A new tab is inserted immediately after the
 * active tab (not appended to the far end), and closing the active tab activates
 * its sequential neighbor (the tab before it, else the one after). Only an
 * explicit {@link #setActive} jumps to an arbitrary tab — nothing else reorders.
 */
public final class TabManager {

    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private int nextId = 0;

    /** Create a new tab, inserted immediately after the active tab. */
    public Tab newTab(boolean isPrivate) {
        return insert(nextId++, isPrivate, "", "");
    }

    /** Create a restored tab with a specific id/url/title (state memory restore). */
    public Tab restoreTab(int id, boolean isPrivate, String url, String title) {
        if (id >= nextId) nextId = id + 1;
        Tab t = insert(id, isPrivate, url, title);
        // restore keeps order; do NOT auto-activate — caller sets the active tab.
        return t;
    }

    private Tab insert(int id, boolean isPrivate, String url, String title) {
        Tab t = new Tab(id, isPrivate);
        t.setUrl(url);
        t.setTitle(title);
        int ai = activeIndex;
        if (ai >= 0 && ai < tabs.size() - 1) {
            tabs.add(ai + 1, t);          // immediately after the active tab
            activeIndex = ai + 1;
        } else {
            tabs.add(t);                  // append (no active tab, or active is last)
            activeIndex = tabs.size() - 1;
        }
        return t;
    }

    public Tab getActive() {
        return activeIndex >= 0 && activeIndex < tabs.size() ? tabs.get(activeIndex) : null;
    }

    public Tab get(int index) {
        return (index >= 0 && index < tabs.size()) ? tabs.get(index) : null;
    }

    /** Find a tab by id (for restore + deep-link routing). */
    public Tab findById(int id) {
        for (Tab t : tabs) if (t.id() == id) return t;
        return null;
    }

    /** Explicitly jump to a tab. This is the ONLY way the active pointer moves non-adjacently. */
    public void setActive(int index) {
        if (index >= 0 && index < tabs.size()) activeIndex = index;
    }

    public void setActiveById(int id) {
        int i = indexOf(findById(id));
        if (i >= 0) activeIndex = i;
    }

    public List<Tab> all() {
        return tabs;
    }

    public int size() {
        return tabs.size();
    }

    public int indexOf(Tab tab) {
        return tabs.indexOf(tab);
    }

    public int nextId() {
        return nextId;
    }

    /** Bump the id counter past any restored ids. */
    public void setNextId(int id) {
        if (id > nextId) nextId = id;
    }

    public void close(int index) {
        if (index < 0 || index >= tabs.size()) return;
        boolean wasActive = index == activeIndex;
        Tab t = tabs.remove(index);
        t.destroy();
        if (tabs.isEmpty()) {
            activeIndex = -1;
        } else if (wasActive) {
            // activate the sequential neighbor: the tab before, else the one after
            activeIndex = Math.max(0, index - 1);
        } else if (activeIndex > index) {
            activeIndex--;
        }
    }

    public void closeById(int id) {
        Tab t = findById(id);
        if (t != null) close(indexOf(t));
    }

    public void closeAll() {
        for (Tab t : tabs) t.destroy();
        tabs.clear();
        activeIndex = -1;
    }
}
