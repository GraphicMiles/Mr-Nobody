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
    /**
     * v2 adds {@code cancel_requested}; v3 adds heartbeat and schedule; v4 adds
     * {@code prev_result} for recurring-task change detection; v5 adds
     * {@code pending_tool} so a WAITING task knows what to resume; v6 adds
     * {@code follow_up} so a reply stays on this task. Every bump needs a
     * real onUpgrade.
     */
    private static final int VERSION = 6;

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
    /** Last time a live worker reported in; distinguishes stuck from dead. */
    private static final String C_BEAT = "last_beat_at";
    private static final String C_REPEAT = "repeat_every";
    private static final String C_LAST_RUN = "last_run_at";
    /** The answer a recurring task produced last run, for change detection. */
    private static final String C_PREV_RESULT = "prev_result";
    /** Tool that is waiting for a human, or null. */
    private static final String C_PENDING_TOOL = "pending_tool";
    private static final String C_FOLLOW_UP = "follow_up";

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
                + C_CANCEL + " INTEGER DEFAULT 0, "
                + C_BEAT + " INTEGER DEFAULT 0, "
                + C_REPEAT + " TEXT, "
                + C_LAST_RUN + " INTEGER DEFAULT 0, "
                + C_PREV_RESULT + " TEXT, "
                + C_PENDING_TOOL + " TEXT, "
                + C_FOLLOW_UP + " TEXT)");
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_BEAT + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_REPEAT + " TEXT");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_LAST_RUN + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_PREV_RESULT + " TEXT");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_PENDING_TOOL + " TEXT");
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_FOLLOW_UP + " TEXT");
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
        v.put(C_FOLLOW_UP, task.followUp());
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(task.id())});
    }

    /**
     * Delete one task row. Exists so a caller that creates a task it does not
     * mean to keep (a diagnostic probe) can remove it again instead of leaving
     * a permanent "Queued" entry in the user's task list.
     */
    public void delete(long id) {
        getWritableDatabase().delete(T, C_ID + "=?", new String[]{String.valueOf(id)});
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
     * Record that a worker is still alive.
     *
     * <p>Separate from {@link #touch(long)} because they answer different
     * questions. A touch says the task changed; a beat says the worker still
     * exists. A task inside one long step never touches, which is exactly when
     * we most need to know it is alive.
     */
    public void beat(long id) {
        ContentValues v = new ContentValues();
        v.put(C_BEAT, System.currentTimeMillis());
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** When a worker last reported for this task, or 0. */
    public long lastBeatAt(long id) {
        try (Cursor c = getReadableDatabase().query(T, new String[]{C_BEAT},
                C_ID + "=?", new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Close out running tasks whose worker has gone silent.
     *
     * <p>Complements {@link #reconcileStale}: that one uses row age, which a
     * healthy task inside a slow step also has. This one only fires when a
     * task claims to be running and has stopped beating, so it can be far more
     * aggressive without killing slow-but-live work.
     *
     * @return how many tasks were recovered
     */
    public int reconcileDead(long deadAfterMs) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put(C_STATUS, Task.Status.FAILED.name());
        v.put(C_ERROR, Heartbeat.recoveredReason());
        v.put(C_STEP, "");
        v.put(C_UPDATED, now);
        return getWritableDatabase().update(
                T, v,
                C_STATUS + " IN (?,?) AND " + C_BEAT + " > 0 AND " + C_BEAT + " < ?",
                new String[]{
                        Task.Status.RUNNING.name(),
                        Task.Status.VERIFYING.name(),
                        String.valueOf(now - deadAfterMs)
                });
    }

    /** Persist how often a task should repeat. */
    public void setSchedule(long id, Schedule.Repeat repeat) {
        ContentValues v = new ContentValues();
        v.put(C_REPEAT, repeat == null ? null : repeat.name());
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    public Schedule.Repeat scheduleOf(long id) {
        try (Cursor c = getReadableDatabase().query(T, new String[]{C_REPEAT},
                C_ID + "=?", new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst()
                    ? Schedule.Repeat.fromName(c.getString(0))
                    : Schedule.Repeat.NEVER;
        } catch (Exception e) {
            return Schedule.Repeat.NEVER;
        }
    }

    public void markRun(long id) {
        ContentValues v = new ContentValues();
        v.put(C_LAST_RUN, System.currentTimeMillis());
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    public long lastRunAt(long id) {
        try (Cursor c = getReadableDatabase().query(T, new String[]{C_LAST_RUN},
                C_ID + "=?", new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** The answer a recurring task produced last run, or "" when it is new. */
    public String previousResult(long id) {
        try (Cursor c = getReadableDatabase().query(T, new String[]{C_PREV_RESULT},
                C_ID + "=?", new String[]{String.valueOf(id)}, null, null, null)) {
            String v = c.moveToFirst() ? c.getString(0) : null;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** The tool a WAITING task needs approved, or "". */
    public String pendingTool(long id) {
        try (Cursor c = getReadableDatabase().query(T, new String[]{C_PENDING_TOOL},
                C_ID + "=?", new String[]{String.valueOf(id)}, null, null, null)) {
            String v = c.moveToFirst() ? c.getString(0) : null;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public void setPendingTool(long id, String tool) {
        ContentValues v = new ContentValues();
        v.put(C_PENDING_TOOL, tool == null ? null : tool);
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Remember what the task last answered, so the next run can say what changed. */
    public void setPreviousResult(long id, String result) {
        ContentValues v = new ContentValues();
        v.put(C_PREV_RESULT, result == null ? "" : result);
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
        // The bounded-retry budget must survive a process restart, or a failed
        // one-shot retries forever after every crash instead of once.
        try { t.setRetryCount(c.getInt(c.getColumnIndexOrThrow(C_RETRY))); }
        catch (Exception ignored) { }
        return t;
    }
}
