package com.mrnobody.agent.planner;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.TokenUsage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dedicated reasoning step that runs before search, fetch or schedule.
 *
 * <p>Taken from DeepSeek Harness's {@code agent/pre-step}: decide what the
 * turn is before the loop spends tools. The previous version scanned for
 * "keep up" and friends. That is the same bug at a longer list.
 */
public final class IntentClassifier {

    private static final long ASK_TIMEOUT_MS = 20_000L;

    static final String SYSTEM_PROMPT =
            "You classify a user's request for a privacy-respecting web assistant. "
                    + "You do not answer the request. You do not pick a tool. "
                    + "You reason about what they want the assistant to *do over time*, "
                    + "then output one JSON object.";

    static final String USER_RUBRIC =
            "Pick exactly one label:\n"
                    + "\n"
                    + "one_time_answer — They want information or an action now. "
                    + "Once it is done, the job is finished. Questions about the "
                    + "present or the past belong here, even if the topic is news.\n"
                    + "\n"
                    + "recurring_monitor — They want to be kept informed as things "
                    + "change or appear. The device should check again later and "
                    + "tell them, not answer once and stop. Ongoing awareness is "
                    + "the point, regardless of how they phrased the request.\n"
                    + "\n"
                    + "named_source_fetch — They want something obtained from a "
                    + "specific site or account they pointed at. The first action "
                    + "must be to open that source, not to search elsewhere.\n"
                    + "\n"
                    + "If a request both names a source and asks to be kept "
                    + "informed, prefer recurring_monitor — the named source is "
                    + "extracted separately.\n"
                    + "\n"
                    + "Reply with JSON only: {\"intent\":\"<label>\"}\n"
                    + "\n"
                    + "Request:\n";

    static final String CANCEL_RUBRIC =
            "The user already has a recurring check running on this conversation. "
                    + "Decide whether this new message is asking to end that check "
                    + "(stop being notified, drop the watch, they are done with it) "
                    + "or is a follow-up that should leave the check in place.\n"
                    + "\n"
                    + "Reply with JSON only: {\"cancel\":true} or {\"cancel\":false}\n"
                    + "\n"
                    + "Message:\n";

    private static final Pattern INTENT_JSON = Pattern.compile(
            "\"intent\"\\s*:\\s*\"([a-zA-Z0-9_\\- ]+)\"");
    private static final Pattern CANCEL_JSON = Pattern.compile(
            "\"cancel\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    private IntentClassifier() {
    }

    public static final class Decision {
        public final TaskIntent intent;
        public final boolean fromModel;

        Decision(TaskIntent intent, boolean fromModel) {
            this.intent = intent == null ? TaskIntent.ONE_TIME_ANSWER : intent;
            this.fromModel = fromModel;
        }
    }

    public static Decision classify(AiProvider provider, String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Decision(TaskIntent.ONE_TIME_ANSWER, false);
        }
        if (provider == null || !provider.isRemote()) {
            return new Decision(TaskIntent.ONE_TIME_ANSWER, false);
        }
        String raw = ask(provider, SYSTEM_PROMPT, USER_RUBRIC + text.trim());
        TaskIntent parsed = parseIntent(raw);
        if (parsed == null) return new Decision(TaskIntent.ONE_TIME_ANSWER, false);
        return new Decision(parsed, true);
    }

    public static boolean wantsCancel(AiProvider provider, String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (provider == null || !provider.isRemote()) return false;
        String raw = ask(provider, SYSTEM_PROMPT, CANCEL_RUBRIC + text.trim());
        return parseCancel(raw);
    }

    public static TaskIntent parseIntent(String raw) {
        if (raw == null) return null;
        String text = stripFences(raw);
        Matcher m = INTENT_JSON.matcher(text);
        if (m.find()) return TaskIntent.fromWire(m.group(1));
        return TaskIntent.fromWire(text.trim());
    }

    public static boolean parseCancel(String raw) {
        if (raw == null) return false;
        Matcher m = CANCEL_JSON.matcher(stripFences(raw));
        return m.find() && "true".equalsIgnoreCase(m.group(1));
    }

    private static String stripFences(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.trim();
    }

    private static String ask(AiProvider provider, String system, String user) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        try {
            provider.complete(system, user, new AiProvider.CompletionCallback() {
                @Override public void onResult(String text) {
                    out[0] = text;
                    latch.countDown();
                }
                @Override public void onError(String error) {
                    latch.countDown();
                }
                @Override public void onUsage(TokenUsage usage) { }
            });
            if (!latch.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out[0];
    }
}
