package com.mrnobody.agent.design;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** SQLite design-session state, independent of any one bounded task run. */
public final class DesignSessionStore extends SQLiteOpenHelper
        implements DesignSessionRepository {

    private static final String DB = "mrnobody_design_sessions.db";
    private static final int VERSION = 1;
    private static final String T = "design_sessions";

    public DesignSessionStore(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "task_id INTEGER NOT NULL UNIQUE,"
                + "platform TEXT NOT NULL,"
                + "artifact_ref TEXT,revision TEXT,candidate_ref TEXT,"
                + "preview_ref TEXT,export_ref TEXT,pending_job_id TEXT,design_spec TEXT,"
                + "status TEXT NOT NULL,safety_gate TEXT NOT NULL,"
                + "creative_gate TEXT NOT NULL,finalization_gate TEXT NOT NULL,"
                + "create_count INTEGER NOT NULL DEFAULT 0,"
                + "edit_count INTEGER NOT NULL DEFAULT 0,"
                + "export_count INTEGER NOT NULL DEFAULT 0,"
                + "poll_count INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_design_status ON " + T + "(status,updated_at)");
        db.execSQL("CREATE TABLE design_quota_effects ("
                + "idempotency_key TEXT PRIMARY KEY,session_id INTEGER NOT NULL,"
                + "operation TEXT NOT NULL,created_at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only; sessions are user work and must never be dropped.
    }

    public DesignSession getOrCreate(long taskId, String spec) {
        DesignSession existing = findByTask(taskId);
        if (existing != null) return existing;
        DesignSession created = DesignSession.create(taskId, spec);
        SQLiteDatabase db = getWritableDatabase();
        long id = db.insertWithOnConflict(T, null, values(created), SQLiteDatabase.CONFLICT_IGNORE);
        if (id >= 0) created.id = id;
        else created = findByTask(taskId);
        return created;
    }

    public DesignSession findByTask(long taskId) {
        try (Cursor c = getReadableDatabase().query(T, null, "task_id=?",
                new String[]{String.valueOf(taskId)}, null, null, null, "1")) {
            return c.moveToFirst() ? read(c) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void update(DesignSession session) {
        if (session == null || session.id <= 0) return;
        // Quota counters are atomically owned by tryConsume; never overwrite
        // them with a stale controller snapshot.
        DesignSession current = findByTask(session.taskId);
        if (current != null) {
            session.createCount = current.createCount;
            session.editCount = current.editCount;
            session.exportCount = current.exportCount;
            session.pollCount = current.pollCount;
        }
        session.updatedAt = System.currentTimeMillis();
        getWritableDatabase().update(T, values(session), "id=?",
                new String[]{String.valueOf(session.id)});
    }

    /** Atomically reserve one operation; replaying the same key is free. */
    @Override
    public boolean tryConsume(long sessionId, DesignQuota.Operation operation,
                              String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) return false;
        String key = idempotencyKey.trim();
        String column = column(operation);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.query("design_quota_effects", new String[]{"session_id"},
                    "idempotency_key=?", new String[]{key}, null, null, null)) {
                if (c.moveToFirst()) {
                    boolean same = c.getLong(0) == sessionId;
                    if (same) db.setTransactionSuccessful();
                    return same;
                }
            }
            int current;
            try (Cursor c = db.query(T, new String[]{column}, "id=?",
                    new String[]{String.valueOf(sessionId)}, null, null, null)) {
                if (!c.moveToFirst()) return false;
                current = c.getInt(0);
            }
            if (current >= DesignQuota.limit(operation)) return false;
            ContentValues effect = new ContentValues();
            effect.put("idempotency_key", key);
            effect.put("session_id", sessionId);
            effect.put("operation", operation.name());
            effect.put("created_at", System.currentTimeMillis());
            if (db.insert("design_quota_effects", null, effect) < 0) return false;
            ContentValues value = new ContentValues();
            value.put(column, current + 1);
            value.put("updated_at", System.currentTimeMillis());
            boolean changed = db.update(T, value, "id=? AND " + column + "=?",
                    new String[]{String.valueOf(sessionId), String.valueOf(current)}) == 1;
            if (changed) db.setTransactionSuccessful();
            return changed;
        } finally {
            try { db.endTransaction(); } catch (Exception ignored) { }
        }
    }

    public void clearTask(long taskId) {
        DesignSession session = findByTask(taskId);
        SQLiteDatabase db = getWritableDatabase();
        if (session != null) {
            db.delete("design_quota_effects", "session_id=?",
                    new String[]{String.valueOf(session.id)});
        }
        db.delete(T, "task_id=?", new String[]{String.valueOf(taskId)});
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("design_quota_effects", null, null);
        db.delete(T, null, null);
    }

    private static String column(DesignQuota.Operation operation) {
        switch (operation) {
            case CREATE: return "create_count";
            case EDIT: return "edit_count";
            case EXPORT: return "export_count";
            case POLL: default: return "poll_count";
        }
    }

    private static ContentValues values(DesignSession s) {
        ContentValues v = new ContentValues();
        v.put("task_id", s.taskId); v.put("platform", s.platform);
        v.put("artifact_ref", s.artifactRef); v.put("revision", s.revision);
        v.put("candidate_ref", s.candidateRef); v.put("preview_ref", s.previewRef);
        v.put("export_ref", s.exportRef); v.put("pending_job_id", s.pendingJobId);
        v.put("design_spec", s.designSpec); v.put("status", s.status.name());
        v.put("safety_gate", s.safetyGate.name());
        v.put("creative_gate", s.creativeGate.name());
        v.put("finalization_gate", s.finalizationGate.name());
        v.put("create_count", s.createCount); v.put("edit_count", s.editCount);
        v.put("export_count", s.exportCount); v.put("poll_count", s.pollCount);
        v.put("created_at", s.createdAt); v.put("updated_at", s.updatedAt);
        return v;
    }

    private static DesignSession read(Cursor c) {
        DesignSession s = new DesignSession();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.taskId = c.getLong(c.getColumnIndexOrThrow("task_id"));
        s.platform = text(c, "platform"); s.artifactRef = text(c, "artifact_ref");
        s.revision = text(c, "revision"); s.candidateRef = text(c, "candidate_ref");
        s.previewRef = text(c, "preview_ref"); s.exportRef = text(c, "export_ref");
        s.pendingJobId = text(c, "pending_job_id"); s.designSpec = text(c, "design_spec");
        try { s.status = DesignSession.Status.valueOf(text(c, "status")); }
        catch (Exception ignored) { s.status = DesignSession.Status.FAILED; }
        s.safetyGate = gate(text(c, "safety_gate"));
        s.creativeGate = gate(text(c, "creative_gate"));
        s.finalizationGate = gate(text(c, "finalization_gate"));
        s.createCount = c.getInt(c.getColumnIndexOrThrow("create_count"));
        s.editCount = c.getInt(c.getColumnIndexOrThrow("edit_count"));
        s.exportCount = c.getInt(c.getColumnIndexOrThrow("export_count"));
        s.pollCount = c.getInt(c.getColumnIndexOrThrow("poll_count"));
        s.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        s.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return s;
    }

    private static ReviewGate gate(String value) {
        try { return ReviewGate.valueOf(value); }
        catch (Exception e) { return ReviewGate.PENDING; }
    }

    private static String text(Cursor c, String column) {
        String value = c.getString(c.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
    }
}
