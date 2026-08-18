package com.mrnobody.browser.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Data Saver grade-to-lever mapping. This is the decision that must not
 * drift: a grade that claims to disable images but does not is a lie, and this
 * is where it is caught before a device is involved.
 */
public class ResourcePolicyTest {

    @Test
    public void gradesAreMonotonicAcrossTheThreeLevers() {
        // OFF: nothing.
        assertFalse(ResourcePolicy.OFF.gatesAutoplay());
        assertFalse(ResourcePolicy.OFF.disablesImages());
        assertFalse(ResourcePolicy.OFF.disablesCache());

        // BALANCED: autoplay only.
        assertTrue(ResourcePolicy.BALANCED.gatesAutoplay());
        assertFalse(ResourcePolicy.BALANCED.disablesImages());
        assertFalse(ResourcePolicy.BALANCED.disablesCache());

        // AGGRESSIVE: autoplay + images.
        assertTrue(ResourcePolicy.AGGRESSIVE.gatesAutoplay());
        assertTrue(ResourcePolicy.AGGRESSIVE.disablesImages());
        assertFalse(ResourcePolicy.AGGRESSIVE.disablesCache());

        // EXTREME: everything.
        assertTrue(ResourcePolicy.EXTREME.gatesAutoplay());
        assertTrue(ResourcePolicy.EXTREME.disablesImages());
        assertTrue(ResourcePolicy.EXTREME.disablesCache());
    }

    @Test
    public void everyGradeDiffersFromEveryOther() {
        ResourcePolicy[] all = ResourcePolicy.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertTrue(all[i].label() + " and " + all[j].label() + " must differ",
                        differs(all[i], all[j]));
            }
        }
    }

    @Test
    public void fromNameParsesAndFallsBack() {
        assertEquals(ResourcePolicy.EXTREME, ResourcePolicy.fromName("extreme"));
        assertEquals(ResourcePolicy.EXTREME, ResourcePolicy.fromName("EXTREME"));
        assertEquals(ResourcePolicy.AGGRESSIVE, ResourcePolicy.fromName("aggressive"));
        assertEquals(ResourcePolicy.OFF, ResourcePolicy.fromName("off"));
        assertEquals(ResourcePolicy.BALANCED, ResourcePolicy.fromName(null));
        assertEquals(ResourcePolicy.BALANCED, ResourcePolicy.fromName("garbage"));
    }

    private static boolean differs(ResourcePolicy a, ResourcePolicy b) {
        return a.gatesAutoplay() != b.gatesAutoplay()
                || a.disablesImages() != b.disablesImages()
                || a.disablesCache() != b.disablesCache();
    }
}
