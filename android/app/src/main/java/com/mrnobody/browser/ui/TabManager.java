package com.mrnobody.browser.ui;

import java.util.ArrayList;
import java.util.List;

/** Owns the list of tabs and the notion of an active tab. */
public final class TabManager {

    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;
    private int nextId = 0;

    public Tab newTab(boolean isPrivate) {
        Tab t = new Tab(nextId++, isPrivate);
        tabs.add(t);
        activeIndex = tabs.size() - 1;
        return t;
    }

    public Tab getActive() {
        return activeIndex >= 0 && activeIndex < tabs.size() ? tabs.get(activeIndex) : null;
    }

    public Tab get(int index) {
        return (index >= 0 && index < tabs.size()) ? tabs.get(index) : null;
    }

    public void setActive(int index) {
        if (index >= 0 && index < tabs.size()) activeIndex = index;
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

    public void close(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.remove(index);
        t.destroy();
        if (tabs.isEmpty()) {
            activeIndex = -1;
        } else if (activeIndex >= tabs.size()) {
            activeIndex = tabs.size() - 1;
        } else if (activeIndex > index) {
            activeIndex--;
        }
    }

    public void closeAll() {
        for (Tab t : tabs) t.destroy();
        tabs.clear();
        activeIndex = -1;
    }
}
