package com.mrnobody.agent.execution;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** SQLite-backed execution ledger; authoritative across process death. */
public final class SqliteExecutionLedger extends SQLiteOpenHelper implements ExecutionLedger {

    private static final String DB = "mrnobody_execution_ledger.db";
    private static final int VERSION = 1;
    private static final String T = "execution_steps";

    public SqliteExecutionLedger(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + "idempotency_key TEXT PRIMARY KEY,"
                + "task_id INTEGER NOT NULL,"
                + "run_id TEXT NOT NULL,"
                + "logical_step_id TEXT NOT NULL,"
                + "effect_slot INTEGER NOT NULL,"
                + "operation_fingerprint TEXT NOT NULL,"
                + "tool TEXT NOT NULL,"
                + "action TEXT NOT NULL,"
                + "tier TEXT NOT NULL,"
                + "state TEXT NOT NULL,"
                + "result_json TEXT,"
                + "external_ref TEXT,"
                + "reserved_cost_micros INTEGER NOT NULL DEFAULT 0,"
                + "actual_cost_micros INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "UNIQUE(task_id,run_id,logical_step_id,effect_slot))");
        db.execSQL("CREATE INDEX idx_execution_run ON " + T
                + "(task_id,run_id,created_at)");
        db.execSQL("CREATE INDEX idx_execution_state ON " + T + "(state)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only. This table is an execution authority and must never be dropped.
    }

    @Override
    public Entry prepare(ExecutionIdentity identity, String tool, String action, Tier tier) {
        if (identity == null || !identity.isDurable()) return null;
        SQLiteDatabase db;
        try {
            db = getWritableDatabase();
        } catch (Exception e) {
            return null;
        }
        db.beginTransaction();
        try {
            Entry existing = find(db, identity.idempotencyKey());
            if (existing != null) {
                db.setTransactionSuccessful();
                return existing;
            }
            long now = System.currentTimeMillis();
            ContentValues v = base(identity);
            v.put("tool", tool == null ? "" : tool);
            v.put("action", action == null ? "" : action);
            v.put("tier", (tier == null ? Tier.READ : tier).name());
            v.put("state", State.PREPARED.name());
            v.put("created_at", now);
            v.put("updated_at", now);
            long inserted = db.insertWithOnConflict(T, null, v, SQLiteDatabase.CONFLICT_IGNORE);
            Entry out = inserted >= 0
                    ? new Entry(identity, tool, action, tier, State.PREPARED,
                            null, "", 0L, 0L, now, now)
                    : find(db, identity.idempotencyKey());
            db.setTransactionSuccessful();
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            try { db.endTransaction(); } catch (Exception ignored) { }
        }
    }

    @Override
    public Entry find(String idempotencyKey) {
        try {
            return find(getReadableDatabase(), idempotencyKey);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void markRunning(ExecutionIdentity identity) {
        updateState(identity, State.RUNNING, null);
    }

    @Override
    public void markWaiting(ExecutionIdentity identity, ToolResult result) {
        updateState(identity, State.WAITING, result);
    }

    @Override
    public void complete(ExecutionIdentity identity, ToolResult result) {
        updateState(identity, result != null && result.isSuccess()
                ? State.SUCCEEDED : State.FAILED, result);
    }

    @Override
    public void markUnknown(ExecutionIdentity identity, String reason) {
        updateState(identity, State.UNKNOWN,
                ToolResult.fail(reason == null ? "outcome unknown" : reason));
    }

    @Override
    public void setExternalRef(ExecutionIdentity identity, String externalRef) {
        ContentValues v = new ContentValues();
        v.put("external_ref", externalRef == null ? "" : externalRef);
        update(identity, v);
    }

    @Override
    public void reserveCost(ExecutionIdentity identity, long micros) {
        ContentValues v = new ContentValues();
        v.put("reserved_cost_micros", Math.max(0L, micros));
        update(identity, v);
    }

    @Override
    public void commitCost(ExecutionIdentity identity, long micros) {
        ContentValues v = new ContentValues();
        v.put("actual_cost_micros", Math.max(0L, micros));
        update(identity, v);
    }

    @Override
    public List<Entry> entriesForRun(long taskId, String runId) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T, null,
                "task_id=? AND run_id=?",
                new String[]{String.valueOf(taskId), runId == null ? "" : runId},
                null, null, "created_at ASC,effect_slot ASC")) {
            while (c.moveToNext()) out.add(read(c));
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public void clearTask(long taskId) {
        try {
            getWritableDatabase().delete(T, "task_id=?",
                    new String[]{String.valueOf(taskId)});
        } catch (Exception ignored) {
        }
    }

    @Override
    public void clearAll() {
        try {
            getWritableDatabase().delete(T, null, null);
        } catch (Exception ignored) {
        }
    }

    private void updateState(ExecutionIdentity identity, State state, ToolResult result) {
        ContentValues v = new ContentValues();
        v.put("state", state.name());
        if (result != null) {
            String encoded = ToolResultCodec.encode(result);
            if (encoded != null) v.put("result_json", encoded);
        }
        update(identity, v);
    }

    private void update(ExecutionIdentity identity, ContentValues values) {
        if (identity == null || !identity.isDurable()) return;
        values.put("updated_at", System.currentTimeMillis());
        try {
            getWritableDatabase().update(T, values, "idempotency_key=?",
                    new String[]{identity.idempotencyKey()});
        } catch (Exception ignored) {
        }
    }

    private static ContentValues base(ExecutionIdentity identity) {
        ContentValues v = new ContentValues();
        v.put("idempotency_key", identity.idempotencyKey());
        v.put("task_id", identity.taskId());
        v.put("run_id", identity.runId());
        v.put("logical_step_id", identity.logicalStepId());
        v.put("effect_slot", identity.effectSlot());
        v.put("operation_fingerprint", identity.operationFingerprint());
        return v;
    }

    private static Entry find(SQLiteDatabase db, String key) {
        if (key == null || key.isEmpty()) return null;
        try (Cursor c = db.query(T, null, "idempotency_key=?",
                new String[]{key}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    private static Entry read(Cursor c) {
        ExecutionIdentity identity = ExecutionIdentity.restore(
                c.getLong(c.getColumnIndexOrThrow("task_id")),
                c.getString(c.getColumnIndexOrThrow("run_id")),
                c.getString(c.getColumnIndexOrThrow("logical_step_id")),
                c.getInt(c.getColumnIndexOrThrow("effect_slot")),
                c.getString(c.getColumnIndexOrThrow("operation_fingerprint")),
                c.getString(c.getColumnIndexOrThrow("idempotency_key")));
        Tier tier;
        State state;
        try { tier = Tier.valueOf(c.getString(c.getColumnIndexOrThrow("tier"))); }
        catch (Exception e) { tier = Tier.READ; }
        try { state = State.valueOf(c.getString(c.getColumnIndexOrThrow("state"))); }
        catch (Exception e) { state = State.UNKNOWN; }
        return new Entry(identity,
                c.getString(c.getColumnIndexOrThrow("tool")),
                c.getString(c.getColumnIndexOrThrow("action")),
                tier, state,
                ToolResultCodec.decode(c.getString(c.getColumnIndexOrThrow("result_json"))),
                c.getString(c.getColumnIndexOrThrow("external_ref")),
                c.getLong(c.getColumnIndexOrThrow("reserved_cost_micros")),
                c.getLong(c.getColumnIndexOrThrow("actual_cost_micros")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("updated_at")));
    }
}
