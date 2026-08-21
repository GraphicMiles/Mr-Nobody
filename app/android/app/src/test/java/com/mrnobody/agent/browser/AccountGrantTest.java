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
        AccountGrant g = AccountGrant.parse(json, "https://x.com/", AccountGrant.Source.PASTED);
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

    @Test
    public void hostOnlyCookieNeverFlowsToParentOrChild() {
        AccountGrant g = AccountGrant.parse("sid=secret", "https://accounts.example.com/login",
                AccountGrant.Source.TAB);
        assertEquals("sid=secret", g.headerForUrl("https://accounts.example.com/home"));
        assertEquals("", g.headerForUrl("https://example.com/"));
        assertEquals("", g.headerForUrl("https://child.accounts.example.com/"));
    }

    @Test
    public void domainCookieMayFlowToChildButNotUnrelatedHost() {
        String json = "[{\"name\":\"sid\",\"value\":\"secret\","
                + "\"domain\":\".example.com\",\"hostOnly\":false,"
                + "\"path\":\"/\",\"secure\":true}]";
        AccountGrant g = AccountGrant.parse(json, "https://example.com/",
                AccountGrant.Source.PASTED);
        assertEquals("sid=secret", g.headerForUrl("https://shop.example.com/cart"));
        assertEquals("", g.headerForUrl("https://example.net/"));
    }

    @Test
    public void secureAndPathBoundariesAreRetained() {
        String json = "[{\"name\":\"sid\",\"value\":\"secret\","
                + "\"domain\":\"example.com\",\"hostOnly\":true,"
                + "\"path\":\"/account\",\"secure\":true}]";
        AccountGrant g = AccountGrant.parse(json, "https://example.com/account",
                AccountGrant.Source.PASTED);
        assertEquals("sid=secret", g.headerForUrl("https://example.com/account/profile"));
        assertEquals("", g.headerForUrl("https://example.com/public"));
        assertEquals("", g.headerForUrl("http://example.com/account/profile"));
    }

    @Test
    public void importedCredentialsNeverTravelOverCleartext() {
        String json = "[{\"name\":\"sid\",\"value\":\"secret\","
                + "\"domain\":\"example.com\",\"hostOnly\":true,\"secure\":false}]";
        AccountGrant g = AccountGrant.parse(json, "https://example.com/",
                AccountGrant.Source.PASTED);
        assertEquals("", g.headerForUrl("http://example.com/"));
        assertEquals("sid=secret", g.headerForUrl("https://example.com/"));
    }

    @Test
    public void grantSiteIsAnOuterBoundaryForParentDomainCookies() {
        String json = "[{\"name\":\"sid\",\"value\":\"secret\","
                + "\"domain\":\".example.com\",\"hostOnly\":false}]";
        AccountGrant g = AccountGrant.parse(json, "https://accounts.example.com/",
                AccountGrant.Source.PASTED);
        assertEquals("sid=secret", g.headerForUrl("https://accounts.example.com/"));
        assertEquals("", g.headerForUrl("https://shop.example.com/"));
    }

    @Test
    public void webViewInjectionRetainsScriptAndSameSiteBoundaries() {
        String json = "[{\"name\":\"sid\",\"value\":\"secret\","
                + "\"domain\":\"example.com\",\"hostOnly\":true,"
                + "\"secure\":true,\"httpOnly\":true,\"sameSite\":\"strict\","
                + "\"expirationDate\":4102444800}]";
        AccountGrant g = AccountGrant.parse(json, "https://example.com/",
                AccountGrant.Source.PASTED);
        String line = g.setCookieLinesForUrl("https://example.com/").get(0);
        assertTrue(line.contains("; Secure"));
        assertTrue(line.contains("; HttpOnly"));
        assertTrue(line.contains("; SameSite=Strict"));
        assertTrue(line.contains("; Expires="));
    }

    @Test
    public void cookieExportStillNeedsAnExplicitGrantSite() {
        String json = "[{\"name\":\"sid\",\"value\":\"secret\","
                + "\"domain\":\".example.com\"}]";
        assertNull(AccountGrant.parse(json, "", AccountGrant.Source.PASTED));
    }

    @Test
    public void wwwHostIsNotCollapsedToItsParent() {
        AccountGrant g = AccountGrant.parse("sid=secret", "https://www.example.com/",
                AccountGrant.Source.TAB);
        assertEquals("www.example.com", g.host);
        assertEquals("", g.headerForUrl("https://example.com/"));
    }
}
