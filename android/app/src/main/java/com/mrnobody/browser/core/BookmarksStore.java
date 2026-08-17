package com.mrnobody.browser.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local bookmarks (V2). Stored on-device only; no synchronization unless a
 * future, explicitly-opted-into sync mechanism is added.
 */
public final class BookmarksStore extends SQLiteOpenHelper {

    private static final String DB = "mrnobody_bookmarks.db";
    private static final int VERSION = 1;

    private static final String TABLE = "bookmarks";
    private static final String COL_ID = "_id";
    private static final String COL_TITLE = "title";
    private static final String COL_URL = "url";
    private static final String COL_FOLDER = "folder";
    private static final String COL_CREATED = "created_at";

    /** A single bookmark. */
    public static final class Bookmark {
        public final long id;
        public final String title;
        public final String url;
        public final String folder;

        Bookmark(long id, String title, String url, String folder) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.folder = folder;
        }
    }

    public BookmarksStore(Context context) {
        super(context, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TITLE + " TEXT NOT NULL, "
                + COL_URL + " TEXT NOT NULL, "
                + COL_FOLDER + " TEXT, "
                + COL_CREATED + " INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // V2 first schema; no migrations yet.
    }

    /** Add a bookmark. Returns the new row id, or -1 on failure. */
    public long add(String title, String url, String folder) {
        if (url == null || url.isEmpty()) return -1;
        ContentValues v = new ContentValues();
        v.put(COL_TITLE, title == null || title.isEmpty() ? url : title);
        v.put(COL_URL, url);
        v.put(COL_FOLDER, folder == null ? "" : folder);
        v.put(COL_CREATED, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE, null, v);
    }

    public void remove(long id) {
        getWritableDatabase().delete(TABLE, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public List<Bookmark> all() {
        List<Bookmark> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, null, null, null, null, COL_CREATED + " DESC")) {
            while (c.moveToNext()) {
                list.add(new Bookmark(
                        c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        c.getString(c.getColumnIndexOrThrow(COL_TITLE)),
                        c.getString(c.getColumnIndexOrThrow(COL_URL)),
                        c.getString(c.getColumnIndexOrThrow(COL_FOLDER))));
            }
        }
        return list;
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            if (c.moveToFirst()) return c.getInt(0);
        }
        return 0;
    }
}
