package com.mrnobody.agent.tasks;

import com.mrnobody.agent.execution.ExecutionIdentity;

import java.util.Locale;

/** Privacy-safe exact fingerprint for short-window task-submission dedup. */
public final class SubmissionFingerprint {

    private SubmissionFingerprint() {
    }

    public static String of(String instruction, String worker, String contextKey) {
        String normalized = normalize(instruction);
        String material = normalized + "\n" + clean(worker).toLowerCase(Locale.ROOT)
                + "\n" + clean(contextKey);
        return ExecutionIdentity.sha256(material);
    }

    static String normalize(String instruction) {
        // Preserve case: prompts may contain case-sensitive code, identifiers,
        // or filenames. Only presentation whitespace is normalized.
        return clean(instruction).replaceAll("\\s+", " ");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
