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
    private static final int VERSION = 1;

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
                + C_WORKER + " TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // V1 first schema.
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
