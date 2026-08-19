package com.mrnobody.agent.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccountGrantTest {

    @Test
    public void aCookieHeaderIsRead() {
        AccountGrant g = AccountGrant.parse(
                "auth_token=abc123; ct0=xyz; Path=/",
                "https://x.com/home",
                AccountGrant.Source.PASTED);
        assertNotNull(g);
        assertEquals("x.com", g.host);
        assertTrue(g.names.contains("auth_token"));
        assertTrue(g.names.contains("ct0"));
        assertTrue(g.header.contains("auth_token=abc123"));
        assertFalse(g.toString().contains("abc123"));
        assertFalse(g.describe().contains("abc123"));
    }

    @Test
    public void cookieEditorJsonIsRead() {
        String json = "[{\"name\":\"auth_token\",\"value\":\"tok\",\"domain\":\".x.com\"},"
                + "{\"name\":\"ct0\",\"value\":\"c\",\"domain\":\".x.com\"}]";
        AccountGrant g = AccountGrant.parse(json, "", AccountGrant.Source.PASTED);
        assertNotNull(g);
        assertEquals("x.com", g.host);
        assertEquals(2, g.names.size());
    }

    @Test
    public void emptyPasteIsRejected() {
        assertNull(AccountGrant.parse("", "x.com", AccountGrant.Source.PASTED));
        assertNull(AccountGrant.parse("not-a-cookie", "x.com", AccountGrant.Source.PASTED));
    }

    @Test
    public void storedRoundTripKeepsTheHeader() {
        AccountGrant g = AccountGrant.parse(
                "sid=hello", "example.com", AccountGrant.Source.TAB);
        AccountGrant back = AccountGrant.fromStored(g.toJson());
        assertNotNull(back);
        assertEquals("example.com", back.host);
        assertEquals("sid=hello", back.header);
        assertEquals(AccountGrant.Source.TAB, back.source);
    }
}
