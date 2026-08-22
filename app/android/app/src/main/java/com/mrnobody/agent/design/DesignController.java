package com.mrnobody.agent.design;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;

import org.json.JSONArray;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Durable draft → creative review → finalization state machine. */
public final class DesignController {

    public interface Invoker {
        ToolResult call(ToolRequest request, Cancellation cancellation);
    }

    public static final class Outcome {
        public final ToolResult toolResult;
        public final String answer;
        public final DesignSession session;

        Outcome(ToolResult toolResult, String answer, DesignSession session) {
            this.toolResult = toolResult;
            this.answer = answer == null ? "" : answer;
            this.session = session;
        }

        public boolean needsApproval() {
            return toolResult != null && toolResult.needsApproval();
        }
        public boolean failed() { return toolResult != null && toolResult.isError(); }
    }

    private final DesignSessionRepository sessions;

    public DesignController(DesignSessionRepository sessions) {
        this.sessions = sessions;
    }

    public Outcome run(Context context, Task task, String instruction,
                       Cancellation cancellation, Invoker invoker) {
        DesignSession session = sessions.getOrCreate(task.id(), instruction);
        if (session == null) return new Outcome(ToolResult.fail("design session unavailable"), "", null);
        String text = instruction == null ? "" : instruction.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        if (isReject(lower)) {
            session.creativeGate = ReviewGate.REJECTED;
            session.status = DesignSession.Status.DRAFTING;
            sessions.update(session);
            return new Outcome(null,
                    "Draft rejected. Tell me what to change and I’ll make a new revision.", session);
        }
        if (isApprove(lower)) {
            if (session.previewRef.isEmpty() && session.artifactRef.isEmpty()) {
                return new Outcome(null, "There is no draft to approve yet.", session);
            }
            session.creativeGate = ReviewGate.APPROVED;
            session.status = DesignSession.Status.READY;
            sessions.update(session);
            return new Outcome(null,
                    "Creative review approved. The design is ready for an explicit export request.",
                    session);
        }
        if (isExport(lower)) {
            if (session.creativeGate != ReviewGate.APPROVED) {
                return new Outcome(null,
                        "Creative review is still pending. Approve the current draft before exporting it.",
                        session);
            }
            if (session.artifactRef.isEmpty()) {
                return new Outcome(null, "Select and create a design candidate before exporting.", session);
            }
            session.finalizationGate = ReviewGate.PENDING;
            session.status = DesignSession.Status.FINALIZING;
            sessions.update(session);
            return invoke(session, "export", text, format(lower), invoker, cancellation);
        }
        if (isSelect(lower, session)) {
            chooseCandidate(session, lower);
            sessions.update(session);
            return invoke(session, "select", text, "", invoker, cancellation);
        }
        if (!session.artifactRef.isEmpty() && isEdit(lower)) {
            session.creativeGate = ReviewGate.PENDING;
            session.status = DesignSession.Status.DRAFTING;
            sessions.update(session);
            return invoke(session, "edit", text, "", invoker, cancellation);
        }
        return invoke(session, "generate", text, "", invoker, cancellation);
    }

    private Outcome invoke(DesignSession session, String action, String instruction,
                           String format, Invoker invoker, Cancellation cancellation) {
        session.safetyGate = ReviewGate.PENDING;
        sessions.update(session);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sessionId", String.valueOf(session.id));
        params.put("instruction", instruction);
        if (!session.candidateRef.isEmpty()) params.put("candidateRef", session.candidateRef);
        if (!session.generationJobId.isEmpty()) params.put("generationJobId", session.generationJobId);
        if (!session.artifactRef.isEmpty()) params.put("artifactRef", session.artifactRef);
        if (!session.revision.isEmpty()) params.put("expectedRevision", session.revision);
        if (!format.isEmpty()) params.put("format", format);

        ToolResult result = invoker.call(new ToolRequest(action, params), cancellation);
        if (result == null) result = ToolResult.fail("design tool returned nothing");
        if (result.needsApproval()) return new Outcome(result, "", session);
        if (result.isError()) {
            session.safetyGate = ReviewGate.REJECTED;
            if ("export".equals(action)) session.finalizationGate = ReviewGate.REJECTED;
            session.status = DesignSession.Status.FAILED;
            sessions.update(session);
            return new Outcome(result, "", session);
        }

        apply(session, action, result);
        sessions.update(session);
        return new Outcome(result, answer(session, action), session);
    }

    private static void apply(DesignSession session, String action, ToolResult result) {
        Map<String, Object> value = result.value();
        session.safetyGate = ReviewGate.APPROVED;
        session.candidateRef = text(value.get("candidateRef"), session.candidateRef);
        Object candidates = value.get("candidates");
        if (candidates instanceof java.util.Collection) {
            session.candidateOptions = new JSONArray((java.util.Collection<?>) candidates).toString();
        }
        session.generationJobId = text(value.get("generationJobId"), session.generationJobId);
        session.artifactRef = text(value.get("artifactRef"), session.artifactRef);
        session.revision = text(value.get("revision"), session.revision);
        session.previewRef = text(value.get("previewRef"), session.previewRef);
        session.exportRef = text(value.get("exportRef"), session.exportRef);
        session.pendingJobId = text(value.get("jobId"), "");
        boolean pending = Boolean.TRUE.equals(value.get("pending"));
        if (pending || !session.pendingJobId.isEmpty()) {
            session.status = "export".equals(action)
                    ? DesignSession.Status.FINALIZING : DesignSession.Status.DRAFTING;
            return;
        }
        if ("export".equals(action)) {
            session.finalizationGate = ReviewGate.APPROVED;
            session.status = DesignSession.Status.FINALIZED;
        } else {
            session.creativeGate = ReviewGate.PENDING;
            session.status = DesignSession.Status.AWAITING_CREATIVE_REVIEW;
        }
    }

    private static String answer(DesignSession session, String action) {
        if (!session.pendingJobId.isEmpty()
                || session.status == DesignSession.Status.FINALIZING) {
            return "The design job was submitted and will continue in the background. "
                    + "You can leave this screen and return later; export progress is also "
                    + "visible in Downloads."
                    + (session.exportRef.isEmpty() ? "" : "\n\n" + session.exportRef);
        }
        if ("export".equals(action)) {
            return "Finalization approved and the export is ready.\n\n" + session.exportRef;
        }
        String ref = session.previewRef.isEmpty() ? session.artifactRef : session.previewRef;
        return "A design draft is ready for creative review. Reply with changes, "
                + "reject it, or say “approve this draft”."
                + (ref.isEmpty() ? "" : "\n\nPreview: " + ref);
    }

    private static void chooseCandidate(DesignSession session, String instruction) {
        if (session.candidateOptions == null || session.candidateOptions.isEmpty()) return;
        int index = instruction.contains("third") ? 2 : instruction.contains("second") ? 1 : 0;
        try {
            JSONArray options = new JSONArray(session.candidateOptions);
            if (options.length() == 0) return;
            org.json.JSONObject chosen = options.optJSONObject(Math.min(index, options.length() - 1));
            if (chosen == null) return;
            session.candidateRef = chosen.optString("candidateRef", session.candidateRef);
            session.previewRef = chosen.optString("previewRef", session.previewRef);
        } catch (Exception ignored) { }
    }

    private static boolean isApprove(String text) {
        return contains(text, "approve", "looks good", "use this", "this is good", "finalize draft");
    }
    private static boolean isReject(String text) {
        return contains(text, "reject", "start over", "discard draft", "don't use", "do not use");
    }
    private static boolean isExport(String text) {
        return contains(text, "export", "download as", "save as", "finalize and");
    }
    private static boolean isEdit(String text) {
        return contains(text, "edit", "change", "replace", "make it", "revise", "update");
    }
    private static boolean isSelect(String text, DesignSession session) {
        return !session.candidateRef.isEmpty()
                && contains(text, "use", "choose", "select", "first", "second", "third");
    }
    private static String format(String text) {
        for (String format : new String[]{"pdf", "png", "jpg", "pptx", "mp4"}) {
            if (text.contains(format)) return format;
        }
        return "png";
    }
    private static boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
    private static String text(Object value, String fallback) {
        if (value == null) return fallback == null ? "" : fallback;
        String clean = String.valueOf(value).trim();
        return clean.isEmpty() || "null".equalsIgnoreCase(clean)
                ? (fallback == null ? "" : fallback) : clean;
    }
}
