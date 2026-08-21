package com.mrnobody.agent.ai;

import com.mrnobody.agent.util.EndpointPolicy;
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
 * A generic OpenAI-compatible chat-completions provider — Groq, OpenRouter,
 * Together, a local llama.cpp server, anything that speaks the same shape.
 *
 * <p>Nothing about a specific model is baked in. The base URL comes from the
 * user, the model comes from {@link #listModels} (the provider's own catalogue,
 * fetched with the user's key), and a request with no model configured fails
 * with a sentence that says so instead of a 404 from someone else's server.
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
    public String modelId() { return model; }

    @Override
    public void complete(String systemPrompt, String userMessage, CompletionCallback callback) {
        new Thread(() -> doComplete(systemPrompt, userMessage, callback)).start();
    }

    @Override
    public void stream(String systemPrompt, String userMessage, StreamCallback callback) {
        streamCancellable(systemPrompt, userMessage, callback);
    }

    @Override
    public RequestHandle streamCancellable(String systemPrompt, String userMessage,
                                           StreamCallback callback) {
        ProviderRequest request = new ProviderRequest();
        request.start("provider-" + id, () -> doStream(systemPrompt, userMessage, callback, request));
        return request;
    }

    /** Why a completion cannot even start, or null when it can. */
    private String configProblem() {
        String endpointProblem = EndpointPolicy.secureBaseReason(baseUrl);
        if (endpointProblem != null) return endpointProblem;
        if (model == null || model.trim().isEmpty()) {
            return "No model chosen for " + displayName
                    + ". Open Settings → AI provider, refresh the model list and pick one.";
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "No API key for " + displayName + ".";
        }
        return null;
    }

    private void doComplete(String system, String user, CompletionCallback callback) {
        String problem = configProblem();
        if (problem != null) {
            callback.onError(problem);
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = post(system, user, false);
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String response = readAll(in);
            if (code < 200 || code >= 300) {
                callback.onError(explain(code, response));
                return;
            }
            JSONObject root = new JSONObject(response);
            String text = root.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            callback.onUsage(usageOf(root));
            callback.onResult(text);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void doStream(String system, String user, StreamCallback callback,
                          ProviderRequest request) {
        String problem = configProblem();
        if (problem != null) {
            if (!request.isCancelled()) callback.onError(problem);
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = post(system, user, true, request);
            if (request.isCancelled()) return;
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                if (!request.isCancelled()) {
                    callback.onError(explain(code, readAll(conn.getErrorStream())));
                }
                return;
            }
            final StringBuilder acc = new StringBuilder();
            final TokenUsage[] usage = {TokenUsage.ZERO};
            try (InputStream in = conn.getInputStream();
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                SseFrames.read(reader, json -> {
                    if (request.isCancelled()) throw new java.io.IOException("cancelled");
                    String delta = deltaContent(json);
                    if (!delta.isEmpty()) {
                        acc.append(delta);
                        callback.onToken(delta);
                    }
                    TokenUsage u = usageOfJson(json);
                    if (u.totalTokens() > 0) usage[0] = u;
                });
            }
            if (request.isCancelled()) return;
            callback.onUsage(usage[0]);
            callback.onDone(acc.toString());
        } catch (Exception e) {
            if (!request.isCancelled()) callback.onError(e.getMessage());
        } finally {
            if (conn != null) {
                request.unbind(conn);
                conn.disconnect();
            }
        }
    }

    /** POST the chat request and return the connection with the body written. */
    private HttpURLConnection post(String system, String user, boolean stream) throws Exception {
        return post(system, user, stream, null);
    }

    private HttpURLConnection post(String system, String user, boolean stream,
                                   ProviderRequest request) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        if (system != null && !system.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", system));
        }
        messages.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", messages);
        body.put("max_tokens", 1024);
        if (stream) {
            body.put("stream", true);
            // Without this, the streaming response omits the usage block, so
            // there would be no authoritative token count to report.
            body.put("stream_options", new JSONObject().put("include_usage", true));
        }

        HttpURLConnection conn = NetworkGate.openHttp(baseUrl + "/chat/completions");
        if (request != null) request.bind(conn);
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            return conn;
        } catch (Exception e) {
            if (request != null) request.unbind(conn);
            conn.disconnect();
            throw e;
        }
    }

    /** The usage block of a response, or {@link TokenUsage#ZERO} when absent. */
    static TokenUsage usageOf(JSONObject root) {
        try {
            JSONObject usage = root.optJSONObject("usage");
            if (usage == null) return TokenUsage.ZERO;
            long prompt = usage.optLong("prompt_tokens", 0);
            long completion = usage.optLong("completion_tokens", 0);
            return new TokenUsage(prompt, completion);
        } catch (Exception e) {
            return TokenUsage.ZERO;
        }
    }

    /** {@link #usageOf} from a raw JSON string (the SSE frame's payload). */
    private static TokenUsage usageOfJson(String json) {
        try {
            return usageOf(new JSONObject(json));
        } catch (Exception e) {
            return TokenUsage.ZERO;
        }
    }

    /**
     * The text delta in one SSE frame's JSON, or "" when the frame carries
     * none (a role-only frame, a usage frame). Package-private for tests.
     */
    static String deltaContent(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) return "";
            return delta.optString("content", "");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void listModels(ModelsCallback callback) {
        new Thread(() -> {
            String endpointProblem = EndpointPolicy.secureBaseReason(baseUrl);
            if (endpointProblem != null) {
                callback.onError(endpointProblem);
                return;
            }
            if (apiKey == null || apiKey.trim().isEmpty()) {
                callback.onError("Add an API key first.");
                return;
            }
            HttpURLConnection conn = null;
            try {
                conn = NetworkGate.openHttp(baseUrl + "/models");
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                int code = conn.getResponseCode();
                InputStream in = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                String response = readAll(in);
                if (code < 200 || code >= 300) {
                    callback.onError(explain(code, response));
                    return;
                }
                JSONArray data = new JSONObject(response).optJSONArray("data");
                List<String> ids = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.optJSONObject(i);
                        if (item == null) continue;
                        String id = item.optString("id", "");
                        if (!id.isEmpty()) ids.add(id);
                    }
                }
                callback.onModels(ModelCatalog.ordered(ids));
            } catch (Exception e) {
                callback.onError(message(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "models-" + id).start();
    }

    /** Turn a provider's HTTP failure into something a person can act on. */
    private String explain(int code, String response) {
        String detail = truncate(response);
        switch (code) {
            case 401:
            case 403:
                return "The API key was rejected by " + displayName + " (HTTP " + code + ").";
            case 404:
                if (detail != null && detail.contains("model")) {
                    return "\"" + model + "\" is not available on this key. Refresh the model "
                            + "list in Settings → AI provider and pick another.";
                }
                return "Not found at " + baseUrl + " (HTTP 404). Check the base URL.";
            case 429:
                return displayName + " is rate-limiting this key (HTTP 429). Try again shortly.";
            default:
                return "HTTP " + code + ": " + detail;
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
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
