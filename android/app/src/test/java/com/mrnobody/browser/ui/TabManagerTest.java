package com.mrnobody.browser.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * JVM tests for the sequential tab model: new tabs insert immediately after the
 * active tab; closing the active tab activates its sequential neighbor (the tab
 * before it, else the one after). Only setActive jumps arbitrarily.
 */
public class TabManagerTest {

    @Test
    public void newTabAppendsWhenNoActive() {
        TabManager m = new TabManager();
        Tab a = m.newTab(false);
        Tab b = m.newTab(false);
        assertEquals(a.id(), m.get(0).id());
        assertEquals(b.id(), m.get(1).id());
    }

    @Test
    public void newTabInsertsAfterActive() {
        TabManager m = new TabManager();
        Tab a = m.newTab(false);   // active
        Tab b = m.newTab(false);   // active (after a)
        Tab c = m.newTab(false);   // active (after b)
        // order: a, b, c
        m.setActive(0);            // jump back to a
        Tab d = m.newTab(false);   // inserted AFTER a → order a, d, b, c
        assertEquals(4, m.size());
        assertEquals(a.id(), m.get(0).id());
        assertEquals(d.id(), m.get(1).id());
        assertEquals(b.id(), m.get(2).id());
        assertEquals(c.id(), m.get(3).id());
        assertEquals(d.id(), m.getActive().id());
    }

    @Test
    public void closeActiveActivatesPreviousNeighbor() {
        TabManager m = new TabManager();
        Tab a = m.newTab(false);
        Tab b = m.newTab(false);
        Tab c = m.newTab(false);   // active = c (index 2)
        m.close(2);                // close c → activate b (index 1, the one before)
        assertEquals(2, m.size());
        assertEquals(b.id(), m.getActive().id());
    }

    @Test
    public void closeFirstActivatesNextNeighbor() {
        TabManager m = new TabManager();
        Tab a = m.newTab(false);
        Tab b = m.newTab(false);
        Tab c = m.newTab(false);
        m.setActive(0);            // active = a (index 0)
        m.close(0);                // no "before" → activate the one after (b)
        assertEquals(b.id(), m.getActive().id());
    }

    @Test
    public void closeInactiveKeepsActive() {
        TabManager m = new TabManager();
        Tab a = m.newTab(false);
        Tab b = m.newTab(false);   // active = b
        m.setActive(0);            // active = a
        m.close(1);                // close inactive b → active stays a
        assertEquals(a.id(), m.getActive().id());
    }

    @Test
    public void closeAllLeavesNoActive() {
        TabManager m = new TabManager();
        m.newTab(false);
        m.newTab(false);
        m.closeAll();
        assertEquals(0, m.size());
        assertNull(m.getActive());
    }
}
