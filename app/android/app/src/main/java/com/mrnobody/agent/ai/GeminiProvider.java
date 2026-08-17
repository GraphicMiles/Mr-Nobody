package com.mrnobody.agent.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Google Gemini via its native generateContent REST API (not the OpenAI
 * compatibility layer). Opt-in; only used when the user configures a key.
 *
 * Base URL and model are configurable; defaults point at Google's free tier
 * (AI Studio): https://generativelanguage.googleapis.com/v1beta
 */
public final class GeminiProvider implements AiProvider {

    /** Free-tier default (Google AI Studio). */
    public static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_MODEL = "gemini-2.0-flash";

    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public GeminiProvider(String apiKey) {
        this(DEFAULT_BASE, DEFAULT_MODEL, apiKey);
    }

    public GeminiProvider(String baseUrl, String model, String apiKey) {
        this.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty())
                ? DEFAULT_BASE : baseUrl.replaceAll("/+$", "");
        this.model = (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model;
        this.apiKey = apiKey;
    }

    @Override
    public String id() { return "gemini"; }

    @Override
    public String displayName() { return "Gemini"; }

    @Override
    public boolean isRemote() { return true; }

    @Override
    public void complete(String systemPrompt, String userMessage, CompletionCallback callback) {
        new Thread(() -> doComplete(systemPrompt, userMessage, callback)).start();
    }

    private void doComplete(String system, String user, CompletionCallback callback) {
        try {
            String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;

            JSONObject contents = new JSONObject();
            JSONArray parts = new JSONArray();
            if (system != null && !system.isEmpty()) {
                parts.put(new JSONObject().put("text", system + "\n\n" + user));
            } else {
                parts.put(new JSONObject().put("text", user));
            }
            contents.put("parts", parts);

            JSONObject body = new JSONObject();
            body.put("contents", new JSONArray().put(contents));

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(in);
            if (code < 200 || code >= 300) {
                callback.onError("HTTP " + code + ": " + truncate(response));
                return;
            }
            String text = new JSONObject(response)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).optString("text", "");
            callback.onResult(text);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 300 ? s.substring(0, 300) : s;
    }
}
