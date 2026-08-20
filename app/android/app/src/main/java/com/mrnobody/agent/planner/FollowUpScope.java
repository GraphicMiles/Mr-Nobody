package com.mrnobody.agent.planner;

import com.mrnobody.agent.core.Task;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides how a message in an existing task relates to the previous turn.
 *
 * <p>A chat thread is not permission to prepend the original research query to
 * every later search. "Who created bitcoin" is standalone; "download the
 * second one" and "why?" require context; "thanks" requires no tool at all.
 * This classifier is intentionally conservative and deterministic. A remote
 * model may reason over context later, but it does not get to turn an
 * acknowledgement into network work.
 */
public final class FollowUpScope {

    public enum Kind { NONE, STANDALONE, CONTEXTUAL, CONVERSATIONAL }

    public static final class Decision {
        public final Kind kind;
        public final String instruction;
        public final String directReply;

        Decision(Kind kind, String instruction, String directReply) {
            this.kind = kind;
            this.instruction = instruction == null ? "" : instruction;
            this.directReply = directReply == null ? "" : directReply;
        }

        public boolean isDirectReply() {
            return kind == Kind.CONVERSATIONAL && !directReply.isEmpty();
        }
    }

    private static final Pattern CONTEXT_PRONOUN = Pattern.compile(
            "\\b(it|its|that|this|they|them|their|those|these|one|ones|above|previous)\\b",
            Pattern.CASE_INSENSITIVE);

    private FollowUpScope() {
    }

    public static Decision decide(Task task, boolean pointsToArtifact) {
        if (task == null) return new Decision(Kind.NONE, "", "");
        String follow = clean(task.followUp());
        if (follow.isEmpty()) {
            return new Decision(Kind.NONE, clean(task.instruction()), "");
        }

        String direct = conversationalReply(follow);
        if (direct != null) {
            return new Decision(Kind.CONVERSATIONAL, follow, direct);
        }

        if (pointsToArtifact || needsContext(follow)) {
            return new Decision(Kind.CONTEXTUAL, task.conversation(), "");
        }
        return new Decision(Kind.STANDALONE, follow, "");
    }

    static boolean needsContext(String text) {
        String t = normal(text);
        if (t.isEmpty()) return false;
        if (TaskArtifact.isPointerFollowUp(t)) return true;
        if (CONTEXT_PRONOUN.matcher(t).find()) return true;
        if (t.startsWith("and ") || t.startsWith("also ")
                || t.startsWith("what about ") || t.startsWith("how about ")
                || t.contains("the sources") || t.contains("your answer")
                || t.contains("the answer") || t.contains("the result")
                || t.contains("the file") || t.contains("the download")) {
            return true;
        }
        return t.equals("why") || t.equals("how") || t.equals("when")
                || t.equals("where") || t.equals("which") || t.equals("who")
                || t.equals("tell me more") || t.equals("explain more")
                || t.equals("go on") || t.equals("continue")
                || t.equals("summarize that") || t.equals("summarise that");
    }

    /** A local response for messages that must never start a research run. */
    static String conversationalReply(String text) {
        String t = normal(text);
        switch (t) {
            case "hi":
            case "hello":
            case "hey":
            case "good morning":
            case "good afternoon":
            case "good evening":
                return "Hi. What would you like me to do next?";
            case "thanks":
            case "thank you":
            case "thank you very much":
            case "thanks a lot":
                return "You're welcome.";
            case "ok":
            case "okay":
            case "got it":
            case "understood":
            case "cool":
            case "nice":
            case "great":
                return "Okay.";
            case "bye":
            case "goodbye":
            case "see you":
                return "Goodbye.";
            default:
                return null;
        }
    }

    private static String normal(String text) {
        String t = clean(text).toLowerCase(Locale.ROOT);
        t = t.replaceAll("^[\\s,!.?]+|[\\s,!.?]+$", "");
        return t.replaceAll("\\s+", " ");
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
