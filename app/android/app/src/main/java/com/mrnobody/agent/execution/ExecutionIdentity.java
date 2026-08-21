package com.mrnobody.agent.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Durable identity for one logical operation inside one task run.
 *
 * <p>A task id alone is not an execution identity: recurring wakes and chat
 * follow-ups deliberately reuse the same task row. The run id separates those
 * cycles; the logical step and effect slot separate multiple operations inside
 * one cycle. Raw parameters are hashed and never retained here.
 */
public final class ExecutionIdentity {

    private final long taskId;
    private final String runId;
    private final String logicalStepId;
    private final int effectSlot;
    private final String operationFingerprint;
    private final String idempotencyKey;

    private ExecutionIdentity(long taskId, String runId, String logicalStepId,
                              int effectSlot, String operationFingerprint,
                              String idempotencyKey) {
        this.taskId = taskId;
        this.runId = clean(runId);
        this.logicalStepId = clean(logicalStepId);
        this.effectSlot = Math.max(0, effectSlot);
        this.operationFingerprint = clean(operationFingerprint);
        this.idempotencyKey = clean(idempotencyKey);
    }

    public static ExecutionIdentity of(long taskId, String runId, String logicalStepId,
                                       int effectSlot, String tool, String action,
                                       Map<String, String> params) {
        String fingerprint = fingerprint(tool, action, params);
        String material = taskId + "\n" + clean(runId) + "\n"
                + clean(logicalStepId) + "\n" + Math.max(0, effectSlot)
                + "\n" + fingerprint;
        return new ExecutionIdentity(taskId, runId, logicalStepId, effectSlot,
                fingerprint, sha256(material));
    }

    /** Restore fields already validated and committed by the ledger. */
    static ExecutionIdentity restore(long taskId, String runId, String logicalStepId,
                                     int effectSlot, String operationFingerprint,
                                     String idempotencyKey) {
        return new ExecutionIdentity(taskId, runId, logicalStepId, effectSlot,
                operationFingerprint, idempotencyKey);
    }

    /** An identity for a host call outside a durable task. It is never ledgered. */
    public static ExecutionIdentity ephemeral(String tool, String action,
                                              Map<String, String> params) {
        String fingerprint = fingerprint(tool, action, params);
        return new ExecutionIdentity(0L, "", "ephemeral", 0, fingerprint, "");
    }

    public long taskId() { return taskId; }
    public String runId() { return runId; }
    public String logicalStepId() { return logicalStepId; }
    public int effectSlot() { return effectSlot; }
    public String operationFingerprint() { return operationFingerprint; }
    public String idempotencyKey() { return idempotencyKey; }

    public boolean isDurable() {
        return taskId > 0 && !runId.isEmpty() && !logicalStepId.isEmpty()
                && !idempotencyKey.isEmpty();
    }

    /** Stable hash of tool + action + sorted arguments. No argument is retained. */
    public static String fingerprint(String tool, String action, Map<String, String> params) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(clean(tool)).append('\n').append(clean(action)).append('\n');
        if (params != null && !params.isEmpty()) {
            List<String> keys = new ArrayList<>(params.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                canonical.append(clean(key)).append('=')
                        .append(params.get(key) == null ? "" : params.get(key))
                        .append('\n');
            }
        }
        return sha256(canonical.toString());
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String safePart(String value) {
        String clean = clean(value).replaceAll("[^A-Za-z0-9._-]", "-");
        if (clean.isEmpty()) return "step";
        return clean.length() <= 48 ? clean : clean.substring(0, 48);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
