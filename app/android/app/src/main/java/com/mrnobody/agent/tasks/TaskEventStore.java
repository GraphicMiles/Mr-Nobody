package com.mrnobody.agent.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * An append-only record of what a task did.
 *
 * <p>Separate from {@link TaskStore} because the two have opposite shapes.
 * A task row is <em>overwritten</em> — status, current step, result — so by the
 * time something goes wrong the evidence of how it got there has already been
 * replaced. Events are only ever inserted, so the history survives.
 *
 * <p><b>The sequence is contiguous per task, not global.</b> A gap therefore
 * means an event was lost rather than "some other task interleaved", which is
 * the property that makes the log worth trusting when reconstructing a
 * failure. Sequences are allocated inside the insert transaction.
 *
 * <p>Unknown event types are kept and returned rather than rejected: a reader
 * from an older build should skip what it does not recognise, not refuse the
 * whole log. That is what lets the type list grow without a migration.
 */
public final class TaskEventStore extends SQLiteOpenHelper {

    private static final String DB = "mrnobody_task_events.db";
    private static final int VERSION = 1;

    private static final String T = "task_events";
    private static final String C_ID = "_id";
    private static final String C_TASK = "task_id";
    private static final String C_SEQ = "seq";
    private static final String C_TYPE = "type";
    private static final String C_DETAIL = "detail";
    private static final String C_AT = "at";

    /** Event kinds. Additive by contract — never renumber or reuse a name. */
    public static final String TASK_STARTED = "task.started";
    public static final String STEP_CHANGED = "step.changed";
    public static final String TOOL_CALL = "tool.call";
    public static final String TOOL_RESULT = "tool.result";
    public static final String TOOL_DENIED = "tool.denied";
    public static final String TASK_FINISHED = "task.finished";
    public static final String TASK_FAILED = "task.failed";
    /** A reply typed in this task's chat — not a new task. */
    public static final String USER_FOLLOWUP = "user.followup";
    /** A finished answer, kept so a follow-up does not erase the previous turn. */
    public static final String AGENT_ANSWER = "agent.answer";

    /** One recorded event. */
    public static final class Event {
        public final long taskId;
        public final long seq;
        public final String type;
        public final String detail;
        public final long at;

        Event(long taskId, long seq, String type, String detail, long at) {
            this.taskId = taskId;
            this.seq = seq;
            this.type = type;
            this.detail = detail;
            this.at = at;
        }
    }

    public TaskEventStore(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_TASK + " INTEGER NOT NULL, "
                + C_SEQ + " INTEGER NOT NULL, "
                + C_TYPE + " TEXT NOT NULL, "
                + C_DETAIL + " TEXT, "
                + C_AT + " INTEGER NOT NULL, "
                // Enforces the contiguity claim in the schema rather than in a
                // comment: a duplicate sequence cannot be written at all.
                + "UNIQUE(" + C_TASK + ", " + C_SEQ + "))");
        db.execSQL("CREATE INDEX idx_events_task ON " + T + "(" + C_TASK + ", " + C_SEQ + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 only. Any future version adds columns or types; it must never
        // drop this table, because the log is the record of what happened.
    }

    /**
     * Append an event.
     *
     * @return the sequence assigned, or -1 if it could not be written. A
     *         failure here must never take a task down: losing a log line is
     *         worse than nothing, but far better than losing the work.
     */
    public long append(long taskId, String type, String detail) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long next = nextSeq(db, taskId);
            ContentValues v = new ContentValues();
            v.put(C_TASK, taskId);
            v.put(C_SEQ, next);
            v.put(C_TYPE, type);
            v.put(C_DETAIL, detail);
            v.put(C_AT, System.currentTimeMillis());
            long rowId = db.insertOrThrow(T, null, v);
            if (rowId < 0) return -1;
            db.setTransactionSuccessful();
            return next;
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                db.endTransaction();
            } catch (Exception ignored) {
                // Nothing useful to do; the insert already failed.
            }
        }
    }

    private long nextSeq(SQLiteDatabase db, long taskId) {
        try (Cursor c = db.rawQuery(
                "SELECT COALESCE(MAX(" + C_SEQ + "), 0) + 1 FROM " + T
                        + " WHERE " + C_TASK + " = ?",
                new String[]{String.valueOf(taskId)})) {
            return c.moveToFirst() ? c.getLong(0) : 1L;
        }
    }

    /** Every event for a task, oldest first. */
    public List<Event> eventsFor(long taskId) {
        List<Event> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T,
                new String[]{C_TASK, C_SEQ, C_TYPE, C_DETAIL, C_AT},
                C_TASK + " = ?", new String[]{String.valueOf(taskId)},
                null, null, C_SEQ + " ASC")) {
            while (c.moveToNext()) {
                out.add(new Event(c.getLong(0), c.getLong(1), c.getString(2),
                        c.getString(3), c.getLong(4)));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * True when a task's sequence numbers run 1..n with no gaps.
     *
     * <p>The log's whole value is that a gap is meaningful, so it should be
     * checkable rather than assumed.
     */
    public boolean isContiguous(long taskId) {
        List<Event> events = eventsFor(taskId);
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).seq != i + 1) return false;
        }
        return true;
    }

    /** Drop a task's events. Used when the user deletes the task. */
    public int clearTask(long taskId) {
        try {
            return getWritableDatabase().delete(T, C_TASK + " = ?",
                    new String[]{String.valueOf(taskId)});
        } catch (Exception e) {
            return 0;
        }
    }

    /** Drop every event. Used when the user forgets all memory. */
    public void clearAll() {
        try {
            getWritableDatabase().delete(T, null, null);
        } catch (Exception e) {
            // Nothing to do: the memory is already gone.
        }
    }
}
