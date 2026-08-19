package com.mrnobody.agent.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UnofficialXLoginTest {

    @Test
    public void isOffByDefault() {
        assertFalse(UnofficialXLogin.isEnabled());
    }

    @Test
    public void aFlippedPrefStillCannotEnableIt() {
        assertFalse(UnofficialXLogin.isEnabled(true));
        assertFalse(UnofficialXLogin.isEnabled(false));
    }

    @Test
    public void refusePointsAtTheGrantPath() {
        String msg = UnofficialXLogin.refuse("login");
        assertTrue(msg, msg.toLowerCase().contains("off"));
        assertTrue(msg, msg.contains("Cookie-Editor") || msg.contains("grant"));
        assertFalse(msg.contains("password"));
    }
}
