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
 * A generic OpenAI-compatible chat-completions provider. Concrete providers
 * (Groq, and any OpenAI-compatible gateway) subclass this with their base URL
 * and default model. Gemini has its own native subclass; see GeminiProvider.
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private final String id;
    private final String displayName;
    private final String baseUrl;   // e.g. https://api.groq.com/openai/v1
    private final String model;
    private final String apiKey;

    public OpenAiCompatibleProvider(String id, String displayName, String baseUrl,
                                    String model, String apiKey) {
        this.id = id;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }

    @Override
    public String id() { return id; }

    @Override
    public String displayName() { return displayName; }

    @Override
    public boolean isRemote() { return true; }

    @Override
    public void complete(String systemPrompt, String userMessage, CompletionCallback callback) {
        new Thread(() -> doComplete(systemPrompt, userMessage, callback)).start();
    }

    private void doComplete(String system, String user, CompletionCallback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            if (system != null && !system.isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", system));
            }
            messages.put(new JSONObject().put("role", "user").put("content", user));
            body.put("messages", messages);
            body.put("max_tokens", 1024);

            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

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
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
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
