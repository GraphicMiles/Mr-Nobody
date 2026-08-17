package com.mrnobody.browser.history;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Local browsing history. By design, {@link #add} is a no-op unless history is
 * enabled, so nothing is persisted by default. History never leaves the device.
 */
public final class HistoryStore extends SQLiteOpenHelper {

    private static final String DB = "mrnobody_history.db";
    private static final int VERSION = 1;

    private static final String TABLE = "history";
    private static final String COL_ID = "_id";
    private static final String COL_URL = "url";
    private static final String COL_TITLE = "title";
    private static final String COL_TS = "visited_at";

    private volatile boolean enabled = false;

    public HistoryStore(Context context) {
        super(context, DB, null, VERSION);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_URL + " TEXT NOT NULL, "
                + COL_TITLE + " TEXT, "
                + COL_TS + " INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // V1: no migrations yet.
    }

    /** Record a visit. No-op when history is disabled. */
    public void add(String url, String title) {
        if (!enabled || url == null || url.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_URL, url);
        values.put(COL_TITLE, title);
        values.put(COL_TS, System.currentTimeMillis());
        db.insert(TABLE, null, values);
    }

    /** Clear all history. */
    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    /** Total stored entries (for tests / debug). */
    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            if (c.moveToFirst()) return c.getInt(0);
        }
        return 0;
    }
}
