package com.mrnobody.agent.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.mrnobody.agent.core.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
     * {@code follow_up} so a reply stays on this task; v7 adds artifacts and
     * the plan snapshot; v8 adds durable run identity and submission dedup;
     * v9 pins provider/fallback/platform configuration per run.
     * Every bump needs a real onUpgrade.
     */
    private static final int VERSION = 9;

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
    private static final String C_ARTIFACTS = "artifacts";
    private static final String C_PLAN = "plan_json";
    private static final String C_RUN_ID = "run_id";
    private static final String C_SUBMISSION_KEY = "submission_key";
    private static final String C_SUBMISSION_FINGERPRINT = "submission_fingerprint";
    private static final String C_PROVIDER_SNAPSHOT = "provider_snapshot";
    private static final String C_FALLBACK_PROVIDER_SNAPSHOTS = "fallback_provider_snapshots";
    private static final String C_EXECUTION_PLATFORM = "execution_platform";

    /** Double taps are duplicates; an explicit later repeat is not. */
    public static final long DEFAULT_DEDUP_WINDOW_MS = 5_000L;

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
                + C_FOLLOW_UP + " TEXT, "
                + C_ARTIFACTS + " TEXT, "
                + C_PLAN + " TEXT, "
                + C_RUN_ID + " TEXT NOT NULL, "
                + C_SUBMISSION_KEY + " TEXT, "
                + C_SUBMISSION_FINGERPRINT + " TEXT, "
                + C_PROVIDER_SNAPSHOT + " TEXT, "
                + C_FALLBACK_PROVIDER_SNAPSHOTS + " TEXT, "
                + C_EXECUTION_PLATFORM + " TEXT)");
        db.execSQL("CREATE UNIQUE INDEX idx_tasks_submission_key ON " + T
                + "(" + C_SUBMISSION_KEY + ")");
        db.execSQL("CREATE INDEX idx_tasks_submission_fingerprint ON " + T
                + "(" + C_SUBMISSION_FINGERPRINT + "," + C_CREATED + ")");
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
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_ARTIFACTS + " TEXT");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_PLAN + " TEXT");
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_RUN_ID + " TEXT");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_SUBMISSION_KEY + " TEXT");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN "
                    + C_SUBMISSION_FINGERPRINT + " TEXT");
            // Existing rows each represent one current cycle. randomblob gives
            // every row a different durable id without exposing task content.
            db.execSQL("UPDATE " + T + " SET " + C_RUN_ID
                    + "=lower(hex(randomblob(16))) WHERE " + C_RUN_ID
                    + " IS NULL OR " + C_RUN_ID + "='' ");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_submission_key ON " + T
                    + "(" + C_SUBMISSION_KEY + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_submission_fingerprint ON " + T
                    + "(" + C_SUBMISSION_FINGERPRINT + "," + C_CREATED + ")");
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_PROVIDER_SNAPSHOT + " TEXT");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN "
                    + C_FALLBACK_PROVIDER_SNAPSHOTS + " TEXT");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_EXECUTION_PLATFORM + " TEXT");
        }
    }

    /** Outcome of an atomic submission: a new row or the live duplicate. */
    public static final class Submission {
        public final long taskId;
        public final boolean created;

        Submission(long taskId, boolean created) {
            this.taskId = taskId;
            this.created = created;
        }
    }

    /** Internal callers explicitly asking for a fresh row bypass short-window dedup. */
    public long insert(String instruction) {
        return insertNew(getWritableDatabase(), instruction, "local",
                UUID.randomUUID().toString(), null, null, System.currentTimeMillis());
    }

    /**
     * Atomically deduplicate one user submission.
     *
     * <p>The client key handles transport replay. The fingerprint catches two
     * UI taps that generated different keys inside a very short window. Only a
     * live task is reused; an explicit later repeat remains a new run.
     */
    public Submission submit(String instruction, String submissionKey, String contextKey) {
        return submit(instruction, submissionKey, contextKey, DEFAULT_DEDUP_WINDOW_MS);
    }

    public Submission submit(String instruction, String submissionKey, String contextKey,
                             long dedupWindowMs) {
        String text = instruction == null ? "" : instruction.trim();
        String key = cleanKey(submissionKey);
        String fingerprint = SubmissionFingerprint.of(text, "local", contextKey);
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long byKey = key == null ? -1L : findBySubmissionKey(db, key);
            if (byKey > 0) {
                db.setTransactionSuccessful();
                return new Submission(byKey, false);
            }
            long duplicate = findLiveFingerprint(db, fingerprint,
                    now - Math.max(0L, dedupWindowMs));
            if (duplicate > 0) {
                db.setTransactionSuccessful();
                return new Submission(duplicate, false);
            }
            long id = insertNew(db, text, "local", UUID.randomUUID().toString(),
                    key, fingerprint, now);
            if (id < 0 && key != null) {
                id = findBySubmissionKey(db, key);
                db.setTransactionSuccessful();
                return new Submission(id, false);
            }
            db.setTransactionSuccessful();
            return new Submission(id, true);
        } catch (Exception e) {
            return new Submission(-1L, false);
        } finally {
            try { db.endTransaction(); } catch (Exception ignored) { }
        }
    }

    private static long insertNew(SQLiteDatabase db, String instruction, String worker,
                                  String runId, String submissionKey,
                                  String fingerprint, long now) {
        ContentValues v = new ContentValues();
        v.put(C_INSTRUCTION, instruction == null ? "" : instruction);
        v.put(C_STATUS, Task.Status.QUEUED.name());
        v.put(C_CREATED, now);
        v.put(C_UPDATED, now);
        v.put(C_RETRY, 0);
        v.put(C_WORKER, worker == null ? "local" : worker);
        v.put(C_RUN_ID, runId);
        v.put(C_SUBMISSION_KEY, submissionKey);
        v.put(C_SUBMISSION_FINGERPRINT, fingerprint);
        return db.insert(T, null, v);
    }

    private static long findBySubmissionKey(SQLiteDatabase db, String key) {
        try (Cursor c = db.query(T, new String[]{C_ID}, C_SUBMISSION_KEY + "=?",
                new String[]{key}, null, null, null, "1")) {
            return c.moveToFirst() ? c.getLong(0) : -1L;
        }
    }

    private static long findLiveFingerprint(SQLiteDatabase db, String fingerprint,
                                            long createdAfter) {
        String live = "(?,?,?,?)";
        try (Cursor c = db.query(T, new String[]{C_ID},
                C_SUBMISSION_FINGERPRINT + "=? AND " + C_CREATED + ">=? AND "
                        + C_STATUS + " IN " + live,
                new String[]{fingerprint, String.valueOf(createdAfter),
                        Task.Status.QUEUED.name(), Task.Status.RUNNING.name(),
                        Task.Status.WAITING.name(), Task.Status.VERIFYING.name()},
                null, null, C_CREATED + " DESC", "1")) {
            return c.moveToFirst() ? c.getLong(0) : -1L;
        }
    }

    private static String cleanKey(String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty()) return null;
        // A key is an opaque identifier, never an instruction-sized payload.
        return clean.length() <= 160 ? clean : clean.substring(0, 160);
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
        v.put(C_RUN_ID, task.runId());
        v.put(C_PROVIDER_SNAPSHOT, task.providerSnapshot());
        v.put(C_FALLBACK_PROVIDER_SNAPSHOTS, task.fallbackProviderSnapshots());
        v.put(C_EXECUTION_PLATFORM, task.executionPlatform());
        v.put(C_FOLLOW_UP, task.followUp());
        v.put(C_ARTIFACTS, task.artifacts());
        v.put(C_PLAN, task.planJson());
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

    /**
     * Tasks that have a live repeating schedule. DeepSeek's schedule_list
     * is this, minus the session-log fold: the durable column is the source
     * of truth.
     */
    public List<Task> monitors() {
        List<Task> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T, null,
                C_REPEAT + " IS NOT NULL AND " + C_REPEAT + " != '' AND "
                        + C_REPEAT + " != ?",
                new String[]{Schedule.Repeat.NEVER.name()},
                null, null, C_UPDATED + " DESC")) {
            while (c.moveToNext()) {
                Task t = fromCursor(c);
                if (scheduleOf(t.id()).isRecurring()) list.add(t);
            }
        } catch (Exception ignored) {
            // An older schema without the column: no monitors.
        }
        return list;
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

    /** Queued rows are a durable scheduling outbox after a process crash. */
    public List<Task> queued() {
        List<Task> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T, null, C_STATUS + "=?",
                new String[]{Task.Status.QUEUED.name()}, null, null, C_CREATED + " ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        } catch (Exception ignored) {
        }
        return list;
    }

    /** Every durable id, used to cancel scheduler state before erasing rows. */
    public List<Long> allIds() {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                T, new String[]{C_ID}, null, null, null, null, null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }

    public void clear() {
        getWritableDatabase().delete(T, null, null);
    }

    private Task fromCursor(Cursor c) {
        long persistedUpdatedAt = c.getLong(c.getColumnIndexOrThrow(C_UPDATED));
        String persistedRunId = "";
        try {
            int runColumn = c.getColumnIndex(C_RUN_ID);
            if (runColumn >= 0) persistedRunId = c.getString(runColumn);
        } catch (Exception ignored) { }
        Task t = new Task(
                c.getLong(c.getColumnIndexOrThrow(C_ID)),
                c.getString(c.getColumnIndexOrThrow(C_INSTRUCTION)),
                c.getLong(c.getColumnIndexOrThrow(C_CREATED)),
                persistedUpdatedAt,
                persistedRunId);
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
        // A reply, the shortlist, and the plan snapshot have to come back
        // with the row. Missing them is how "download it" forgot the first
        // turn and how the chat lost the cards after a process death.
        try {
            int i = c.getColumnIndex(C_FOLLOW_UP);
            if (i >= 0) t.setFollowUp(c.getString(i));
        } catch (Exception ignored) { }
        try {
            int i = c.getColumnIndex(C_ARTIFACTS);
            if (i >= 0) t.setArtifacts(c.getString(i));
        } catch (Exception ignored) { }
        try {
            int i = c.getColumnIndex(C_PLAN);
            if (i >= 0) t.setPlanJson(c.getString(i));
        } catch (Exception ignored) { }
        try {
            int i = c.getColumnIndex(C_PROVIDER_SNAPSHOT);
            if (i >= 0) t.setProviderSnapshot(c.getString(i));
        } catch (Exception ignored) { }
        try {
            int i = c.getColumnIndex(C_FALLBACK_PROVIDER_SNAPSHOTS);
            if (i >= 0) t.setFallbackProviderSnapshots(c.getString(i));
        } catch (Exception ignored) { }
        try {
            int i = c.getColumnIndex(C_EXECUTION_PLATFORM);
            if (i >= 0) t.setExecutionPlatform(c.getString(i));
        } catch (Exception ignored) { }
        // The restoration setters above update the in-memory timestamp. Put
        // the durable value back after every field has been loaded.
        t.restoreUpdatedAt(persistedUpdatedAt);
        return t;
    }
}
