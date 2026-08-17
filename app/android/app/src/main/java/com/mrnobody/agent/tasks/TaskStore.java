package com.mrnobody.agent.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.mrnobody.agent.core.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable task storage (SQLite). Task state survives process death so a task
 * can resume after the app is closed — never depend on a process staying alive.
 */
public final class TaskStore extends SQLiteOpenHelper {

    private static final String DB = "mrnobody_tasks.db";
    /** v2 adds {@code cancel_requested}. Every bump needs a real onUpgrade. */
    private static final int VERSION = 2;

    private static final String T = "tasks";
    private static final String C_ID = "_id";
    private static final String C_INSTRUCTION = "instruction";
    private static final String C_STATUS = "status";
    private static final String C_STEP = "current_step";
    private static final String C_RESULT = "result";
    private static final String C_ERROR = "error";
    private static final String C_CREATED = "created_at";
    private static final String C_UPDATED = "updated_at";
    private static final String C_RETRY = "retry_count";
    private static final String C_WORKER = "worker";
    private static final String C_CANCEL = "cancel_requested";

    public TaskStore(Context context) {
        super(context, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_INSTRUCTION + " TEXT NOT NULL, "
                + C_STATUS + " TEXT NOT NULL, "
                + C_STEP + " TEXT, "
                + C_RESULT + " TEXT, "
                + C_ERROR + " TEXT, "
                + C_CREATED + " INTEGER NOT NULL, "
                + C_UPDATED + " INTEGER NOT NULL, "
                + C_RETRY + " INTEGER DEFAULT 0, "
                + C_WORKER + " TEXT, "
                + C_CANCEL + " INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrations are cumulative and each one must be safe to run against a
        // database that real tasks already live in. An empty onUpgrade would
        // hand an existing install a schema the code expects and the database
        // does not have.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_CANCEL + " INTEGER DEFAULT 0");
        }
    }

    public long insert(String instruction) {
        ContentValues v = new ContentValues();
        v.put(C_INSTRUCTION, instruction);
        v.put(C_STATUS, Task.Status.QUEUED.name());
        long now = System.currentTimeMillis();
        v.put(C_CREATED, now);
        v.put(C_UPDATED, now);
        v.put(C_RETRY, 0);
        v.put(C_WORKER, "local");
        return getWritableDatabase().insert(T, null, v);
    }

    public void update(Task task) {
        ContentValues v = new ContentValues();
        v.put(C_STATUS, task.status().name());
        v.put(C_STEP, task.currentStep());
        v.put(C_RESULT, task.result());
        v.put(C_ERROR, task.error());
        v.put(C_UPDATED, System.currentTimeMillis());
        v.put(C_RETRY, task.retryCount());
        v.put(C_WORKER, task.worker());
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(task.id())});
    }

    /**
     * Record that the user asked for this task to stop. Durable on purpose: the
     * worker may be in another process, or not running yet, and the request has
     * to survive until something observes it.
     */
    public void requestCancel(long id) {
        ContentValues v = new ContentValues();
        v.put(C_CANCEL, 1);
        v.put(C_UPDATED, System.currentTimeMillis());
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Whether a cancel request is outstanding for this task. */
    public boolean isCancelRequested(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(T, new String[]{C_CANCEL}, C_ID + "=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() && c.getInt(0) != 0;
        }
    }

    /** Clear the flag once it has been acted on, so a re-run is not born cancelled. */
    public void clearCancelRequest(long id) {
        ContentValues v = new ContentValues();
        v.put(C_CANCEL, 0);
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    /**
     * Heartbeat: stamp the row so reconciliation can tell a long step from a
     * dead process. Cheap enough to call on a timer while work is in flight.
     */
    public void touch(long id) {
        ContentValues v = new ContentValues();
        v.put(C_UPDATED, System.currentTimeMillis());
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    /**
     * Close out tasks whose worker died. Called at startup and before a worker
     * takes new work; see {@link TaskReconciler} for the rule.
     *
     * @return how many tasks were reconciled
     */
    public int reconcileStale(long staleAfterMs) {
        long now = System.currentTimeMillis();
        long cutoff = now - staleAfterMs;
        ContentValues v = new ContentValues();
        v.put(C_STATUS, Task.Status.FAILED.name());
        v.put(C_ERROR, TaskReconciler.interruptedReason());
        v.put(C_STEP, "");
        v.put(C_UPDATED, now);
        return getWritableDatabase().update(
                T, v,
                C_STATUS + " IN (?,?) AND " + C_UPDATED + " < ?",
                new String[]{
                        Task.Status.RUNNING.name(),
                        Task.Status.VERIFYING.name(),
                        String.valueOf(cutoff)
                });
    }

    public Task get(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(T, null, C_ID + "=?", new String[]{String.valueOf(id)},
                null, null, null)) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        return null;
    }

    public List<Task> recent(int limit) {
        List<Task> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(T, null, null, null, null, null,
                C_CREATED + " DESC", String.valueOf(limit))) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public void clear() {
        getWritableDatabase().delete(T, null, null);
    }

    private Task fromCursor(Cursor c) {
        Task t = new Task(
                c.getLong(c.getColumnIndexOrThrow(C_ID)),
                c.getString(c.getColumnIndexOrThrow(C_INSTRUCTION)));
        try { t.setStatus(Task.Status.valueOf(c.getString(c.getColumnIndexOrThrow(C_STATUS)))); }
        catch (Exception ignored) { }
        t.setCurrentStep(c.getString(c.getColumnIndexOrThrow(C_STEP)));
        t.setResult(c.getString(c.getColumnIndexOrThrow(C_RESULT)));
        t.setError(c.getString(c.getColumnIndexOrThrow(C_ERROR)));
        t.setWorker(c.getString(c.getColumnIndexOrThrow(C_WORKER)));
        return t;
    }
}
