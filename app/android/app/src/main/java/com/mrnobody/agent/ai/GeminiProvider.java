package com.mrnobody.agent.ai;

import com.mrnobody.browser.net.NetworkGate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Gemini via its native generateContent REST API (not the OpenAI
 * compatibility layer). Opt-in; only used when the user configures a key.
 *
 * Base URL and model are configurable; defaults point at Google's free tier
 * (AI Studio): https://generativelanguage.googleapis.com/v1beta
 */
public final class GeminiProvider implements AiProvider {

    /** Google AI Studio's endpoint. Suggested, and still editable. */
    public static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final String baseUrl;
    private final String model;
    private final String apiKey;

    public GeminiProvider(String apiKey) {
        this(DEFAULT_BASE, "", apiKey);
    }

    public GeminiProvider(String baseUrl, String model, String apiKey) {
        this.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty())
                ? DEFAULT_BASE : baseUrl.replaceAll("/+$", "");
        // No default: model ids are the first thing a provider retires, so the
        // list is fetched from the account and the user chooses.
        this.model = model == null ? "" : ModelCatalog.stripPrefix(model.trim());
        this.apiKey = apiKey;
    }

    @Override
    public String id() { return "gemini"; }

    @Override
    public String displayName() { return "Gemini"; }

    @Override
    public boolean isRemote() { return true; }

    @Override
    public String modelId() { return model; }

    @Override
    public void complete(String systemPrompt, String userMessage, CompletionCallback callback) {
        new Thread(() -> doComplete(systemPrompt, userMessage, callback)).start();
    }

    @Override
    public void stream(String systemPrompt, String userMessage, StreamCallback callback) {
        new Thread(() -> doStream(systemPrompt, userMessage, callback)).start();
    }

    /** Why a completion cannot even start, or null when it can. */
    private String configProblem() {
        if (model.isEmpty()) {
            return "No model chosen for Gemini. Open Settings → AI provider, "
                    + "refresh the model list and pick one.";
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "No API key for Gemini.";
        }
        return null;
    }

    private void doComplete(String system, String user, CompletionCallback callback) {
        String problem = configProblem();
        if (problem != null) {
            callback.onError(problem);
            return;
        }
        try {
            HttpURLConnection conn = post(system, user, false);
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(in);
            if (code < 200 || code >= 300) {
                callback.onError(explain(code, response));
                return;
            }
            JSONObject root = new JSONObject(response);
            String text = root.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).optString("text", "");
            callback.onUsage(usageOf(root));
            callback.onResult(text);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private void doStream(String system, String user, StreamCallback callback) {
        String problem = configProblem();
        if (problem != null) {
            callback.onError(problem);
            return;
        }
        try {
            HttpURLConnection conn = post(system, user, true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                callback.onError(explain(code, readAll(conn.getErrorStream())));
                return;
            }
            final StringBuilder acc = new StringBuilder();
            final TokenUsage[] usage = {TokenUsage.ZERO};
            try (InputStream in = conn.getInputStream();
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                SseFrames.read(reader, json -> {
                    String text = candidateText(json);
                    if (!text.isEmpty()) {
                        acc.append(text);
                        callback.onToken(text);
                    }
                    // streamGenerateContent carries usageMetadata on chunks;
                    // keep the most recent non-zero figure.
                    TokenUsage u = usageOf(new JSONObject(json));
                    if (u.totalTokens() > 0) usage[0] = u;
                });
            }
            callback.onUsage(usage[0]);
            callback.onDone(acc.toString());
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    /** POST the generateContent request and return the connection, body written. */
    private HttpURLConnection post(String system, String user, boolean stream) throws Exception {
        // Streaming goes through streamGenerateContent; the one-shot path
        // through generateContent. Both accept the same body.
        String action = stream ? ":streamGenerateContent?alt=sse&key=" : ":generateContent?key=";
        String url = baseUrl + "/models/" + model + action + apiKey;

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

        HttpURLConnection conn = NetworkGate.openHttp(url);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    /** The usageMetadata block of a response, or {@link TokenUsage#ZERO} when absent. */
    static TokenUsage usageOf(JSONObject root) {
        try {
            JSONObject meta = root.optJSONObject("usageMetadata");
            if (meta == null) return TokenUsage.ZERO;
            long prompt = meta.optLong("promptTokenCount", 0);
            long completion = meta.optLong("candidatesTokenCount", 0);
            return new TokenUsage(prompt, completion);
        } catch (Exception e) {
            return TokenUsage.ZERO;
        }
    }

    /**
     * The text in one SSE frame's JSON, or "" when the frame carries none.
     * Package-private for tests.
     */
    static String candidateText(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray candidates = obj.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return "";
            JSONArray parts = candidates.getJSONObject(0)
                    .optJSONObject("content").optJSONArray("parts");
            if (parts == null || parts.length() == 0) return "";
            return parts.getJSONObject(0).optString("text", "");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void listModels(ModelsCallback callback) {
        new Thread(() -> {
            if (apiKey == null || apiKey.trim().isEmpty()) {
                callback.onError("Add an API key first.");
                return;
            }
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        NetworkGate.openHttp(baseUrl + "/models?key=" + apiKey);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);
                int code = conn.getResponseCode();
                InputStream in = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                String response = readAll(in);
                if (code < 200 || code >= 300) {
                    callback.onError(explain(code, response));
                    return;
                }
                JSONArray models = new JSONObject(response).optJSONArray("models");
                List<String> ids = new ArrayList<>();
                if (models != null) {
                    for (int i = 0; i < models.length(); i++) {
                        JSONObject m = models.optJSONObject(i);
                        if (m == null) continue;
                        // Only models that can answer a prompt: the same list
                        // carries embedding and media models.
                        JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                        boolean generates = methods == null;
                        if (methods != null) {
                            for (int j = 0; j < methods.length(); j++) {
                                if ("generateContent".equals(methods.optString(j))) {
                                    generates = true;
                                    break;
                                }
                            }
                        }
                        if (!generates) continue;
                        String name = ModelCatalog.stripPrefix(m.optString("name", ""));
                        if (!name.isEmpty()) ids.add(name);
                    }
                }
                callback.onModels(ModelCatalog.ordered(ids));
            } catch (Exception e) {
                String m = e.getMessage();
                callback.onError(m == null ? e.getClass().getSimpleName() : m);
            }
        }, "models-gemini").start();
    }

    /** Turn a Google API failure into something a person can act on. */
    private String explain(int code, String response) {
        String detail = truncate(response);
        switch (code) {
            case 400:
                return "Gemini rejected the request (HTTP 400): " + detail;
            case 401:
            case 403:
                return "The API key was rejected by Gemini (HTTP " + code + ").";
            case 404:
                return "\"" + model + "\" is not available on this key. Refresh the model list "
                        + "in Settings → AI provider and pick another.";
            case 429:
                return "Gemini is rate-limiting this key (HTTP 429). Try again shortly.";
            default:
                return "HTTP " + code + ": " + detail;
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
