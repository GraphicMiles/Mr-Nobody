package com.mrnobody.agent.mcp;

import android.content.Context;

import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.security.EncryptedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-user OAuth 2.1 + PKCE for Canva's remote MCP. */
public final class CanvaOAuthManager implements McpClient.TokenProvider {

    private static final String TOKEN = "canva_mcp_token";
    private static final String PENDING = "canva_mcp_pending";
    private static final String ERROR = "canva_mcp_error";
    private static final long PENDING_MAX_MS = 10 * 60_000L;

    private final EncryptedPreferences secrets;
    private final SecureRandom random = new SecureRandom();

    public CanvaOAuthManager(Context context) {
        secrets = new EncryptedPreferences(context, "mrnobody_mcp_credentials",
                "mrnobody_mcp_credentials_v1");
    }

    public synchronized String beginAuthorization() throws Exception {
        if (!CanvaMcpConfig.isBuildConfigured()) {
            throw new IllegalStateException("Canva MCP needs an approved CIMD URL in the build.");
        }
        String state = randomUrl(32);
        String verifier = randomUrl(48);
        String challenge = base64Url(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        if (!secrets.putString(PENDING,
                new OAuthPending(state, verifier, System.currentTimeMillis()).encode())) {
            throw new IllegalStateException("Could not securely store the OAuth state");
        }
        secrets.remove(ERROR);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", CanvaMcpConfig.clientId());
        query.put("redirect_uri", CanvaMcpConfig.redirectUri());
        query.put("state", state);
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        query.put("resource", CanvaMcpConfig.ENDPOINT);
        return CanvaMcpConfig.AUTHORIZE_ENDPOINT + "?" + form(query);
    }

    public boolean isCallback(String callbackUri) {
        try {
            return sameRedirect(new URI(CanvaMcpConfig.redirectUri()), new URI(callbackUri));
        } catch (Exception e) { return false; }
    }

    public synchronized boolean handleRedirect(String callbackUri) {
        if (!isCallback(callbackUri)) return false;
        try {
            URI callback = new URI(callbackUri);
            Map<String, String> query = query(callback.getRawQuery());
            OAuthPending pending = OAuthPending.decode(secrets.getString(PENDING, ""));
            if (pending.state.isEmpty() || !constantTime(pending.state, query.get("state"))
                    || System.currentTimeMillis() - pending.createdAt > PENDING_MAX_MS) {
                fail("Canva authorization state was invalid or expired.");
                return true;
            }
            String oauthError = query.get("error");
            if (oauthError != null && !oauthError.isEmpty()) {
                fail("Canva authorization was declined: " + oauthError);
                return true;
            }
            String code = query.get("code");
            if (code == null || code.isEmpty()) {
                fail("Canva authorization returned no code.");
                return true;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("grant_type", "authorization_code");
            fields.put("code", code);
            fields.put("client_id", CanvaMcpConfig.clientId());
            fields.put("redirect_uri", CanvaMcpConfig.redirectUri());
            fields.put("code_verifier", pending.verifier);
            fields.put("resource", CanvaMcpConfig.ENDPOINT);
            OAuthToken token = exchange(fields);
            if (!secrets.putString(TOKEN, token.encode())) {
                fail("Could not securely store the Canva authorization.");
            } else {
                secrets.remove(PENDING);
                secrets.remove(ERROR);
            }
            return true;
        } catch (Exception e) {
            fail("Canva authorization failed: " + safe(e));
            return true;
        }
    }

    @Override
    public synchronized String accessToken() throws Exception {
        OAuthToken token = OAuthToken.decode(secrets.getString(TOKEN, ""));
        if (token.usable(System.currentTimeMillis())) return token.accessToken;
        if (token.refreshToken.isEmpty()) throw new IllegalStateException("Canva is not connected");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", token.refreshToken);
        body.put("client_id", CanvaMcpConfig.clientId());
        body.put("resource", CanvaMcpConfig.ENDPOINT);
        OAuthToken refreshed = exchange(body);
        if (refreshed.refreshToken.isEmpty()) {
            refreshed = new OAuthToken(refreshed.accessToken, token.refreshToken,
                    refreshed.tokenType, refreshed.scope, refreshed.expiresAt);
        }
        if (!secrets.putString(TOKEN, refreshed.encode())) {
            throw new IllegalStateException("Could not securely refresh Canva authorization");
        }
        return refreshed.accessToken;
    }

    public synchronized boolean isConnected() {
        OAuthToken token = OAuthToken.decode(secrets.getString(TOKEN, ""));
        return !token.accessToken.isEmpty() || !token.refreshToken.isEmpty();
    }

    public synchronized String lastError() { return secrets.getString(ERROR, ""); }

    public synchronized void disconnect() {
        secrets.remove(TOKEN); secrets.remove(PENDING); secrets.remove(ERROR);
    }

    private OAuthToken exchange(Map<String, String> fields) throws Exception {
        HttpURLConnection connection = NetworkGate.openHttp(CanvaMcpConfig.TOKEN_ENDPOINT);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(form(fields).getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = read(stream);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Canva token endpoint returned HTTP " + status);
            }
            JSONObject json = new JSONObject(body);
            String access = json.optString("access_token", "");
            if (access.isEmpty()) throw new java.io.IOException("Canva returned no access token");
            long seconds = Math.max(60L, json.optLong("expires_in", 3600L));
            return new OAuthToken(access, json.optString("refresh_token", ""),
                    json.optString("token_type", "Bearer"), json.optString("scope", ""),
                    System.currentTimeMillis() + seconds * 1000L);
        } finally {
            connection.disconnect();
        }
    }

    private void fail(String message) {
        secrets.remove(PENDING);
        secrets.putString(ERROR, message);
    }

    private String randomUrl(int bytes) {
        byte[] value = new byte[bytes]; random.nextBytes(value); return base64Url(value);
    }
    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    private static String form(Map<String, String> values) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return out.toString();
    }
    private static Map<String, String> query(String raw) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], "UTF-8");
            String value = parts.length > 1
                    ? java.net.URLDecoder.decode(parts[1], "UTF-8") : "";
            out.put(key, value);
        }
        return out;
    }
    private static boolean sameRedirect(URI expected, URI actual) {
        String expectedPath = expected.getPath() == null ? "" : expected.getPath();
        String actualPath = actual.getPath() == null ? "" : actual.getPath();
        return eq(expected.getScheme(), actual.getScheme())
                && eq(expected.getAuthority(), actual.getAuthority())
                && expectedPath.equals(actualPath);
    }
    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equalsIgnoreCase(b == null ? "" : b);
    }
    private static boolean constantTime(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096]; int n;
            while ((n = reader.read(buffer)) != -1 && out.length() < 64 * 1024) {
                out.append(buffer, 0, Math.min(n, 64 * 1024 - out.length()));
            }
        }
        return out.toString();
    }
    private static String safe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
