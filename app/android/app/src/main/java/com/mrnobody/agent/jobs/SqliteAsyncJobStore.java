package com.mrnobody.agent.jobs;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Durable store for generic external jobs. */
public final class SqliteAsyncJobStore extends SQLiteOpenHelper implements AsyncJobStore {

    private static final String DB = "mrnobody_async_jobs.db";
    private static final int VERSION = 1;
    private static final String T = "async_jobs";

    public SqliteAsyncJobStore(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + "local_job_id TEXT PRIMARY KEY,"
                + "task_id INTEGER NOT NULL,"
                + "run_id TEXT NOT NULL,"
                + "adapter_id TEXT NOT NULL,"
                + "idempotency_key TEXT NOT NULL UNIQUE,"
                + "operation_fingerprint TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "external_job_id TEXT,"
                + "result_ref TEXT,"
                + "error TEXT,"
                + "next_poll_at INTEGER NOT NULL DEFAULT 0,"
                + "reserved_cost_micros INTEGER NOT NULL DEFAULT 0,"
                + "actual_cost_micros INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_async_pending ON " + T + "(status,next_poll_at)");
        db.execSQL("CREATE INDEX idx_async_task ON " + T + "(task_id,run_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only; jobs are recovery state and must never be dropped.
    }

    @Override
    public boolean create(AsyncJob job) {
        if (job == null || job.idempotencyKey.isEmpty()) return false;
        try {
            return getWritableDatabase().insertWithOnConflict(
                    T, null, values(job), SQLiteDatabase.CONFLICT_IGNORE) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public AsyncJob findByIdempotencyKey(String key) {
        return findWhere("idempotency_key=?", key);
    }

    @Override
    public AsyncJob find(String localJobId) {
        return findWhere("local_job_id=?", localJobId);
    }

    @Override
    public void update(AsyncJob job) {
        if (job == null) return;
        try {
            getWritableDatabase().update(T, values(job), "local_job_id=?",
                    new String[]{job.localJobId});
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<AsyncJob> pending() {
        List<AsyncJob> out = new ArrayList<>();
        String terminal = "(?,?,?)";
        try (Cursor c = getReadableDatabase().query(T, null,
                "status NOT IN " + terminal,
                new String[]{AsyncJob.Status.SUCCEEDED.name(),
                        AsyncJob.Status.FAILED.name(), AsyncJob.Status.CANCELLED.name()},
                null, null, "next_poll_at ASC,created_at ASC")) {
            while (c.moveToNext()) out.add(read(c));
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public void clearTask(long taskId) {
        try {
            getWritableDatabase().delete(T, "task_id=?", new String[]{String.valueOf(taskId)});
        } catch (Exception ignored) {
        }
    }

    @Override
    public void clearAll() {
        try { getWritableDatabase().delete(T, null, null); }
        catch (Exception ignored) { }
    }

    private AsyncJob findWhere(String where, String value) {
        if (value == null || value.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().query(T, null, where,
                new String[]{value}, null, null, null, "1")) {
            return c.moveToFirst() ? read(c) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ContentValues values(AsyncJob job) {
        ContentValues v = new ContentValues();
        v.put("local_job_id", job.localJobId);
        v.put("task_id", job.taskId);
        v.put("run_id", job.runId);
        v.put("adapter_id", job.adapterId);
        v.put("idempotency_key", job.idempotencyKey);
        v.put("operation_fingerprint", job.operationFingerprint);
        v.put("status", job.status.name());
        v.put("external_job_id", job.externalJobId);
        v.put("result_ref", job.resultRef);
        v.put("error", job.error);
        v.put("next_poll_at", job.nextPollAt);
        v.put("reserved_cost_micros", job.reservedCostMicros);
        v.put("actual_cost_micros", job.actualCostMicros);
        v.put("created_at", job.createdAt);
        v.put("updated_at", job.updatedAt);
        return v;
    }

    private static AsyncJob read(Cursor c) {
        AsyncJob.Status status;
        try { status = AsyncJob.Status.valueOf(c.getString(c.getColumnIndexOrThrow("status"))); }
        catch (Exception e) { status = AsyncJob.Status.UNKNOWN; }
        return new AsyncJob(
                c.getString(c.getColumnIndexOrThrow("local_job_id")),
                c.getLong(c.getColumnIndexOrThrow("task_id")),
                c.getString(c.getColumnIndexOrThrow("run_id")),
                c.getString(c.getColumnIndexOrThrow("adapter_id")),
                c.getString(c.getColumnIndexOrThrow("idempotency_key")),
                c.getString(c.getColumnIndexOrThrow("operation_fingerprint")),
                status,
                c.getString(c.getColumnIndexOrThrow("external_job_id")),
                c.getString(c.getColumnIndexOrThrow("result_ref")),
                c.getString(c.getColumnIndexOrThrow("error")),
                c.getLong(c.getColumnIndexOrThrow("next_poll_at")),
                c.getLong(c.getColumnIndexOrThrow("reserved_cost_micros")),
                c.getLong(c.getColumnIndexOrThrow("actual_cost_micros")),
                c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("updated_at")));
    }
}
