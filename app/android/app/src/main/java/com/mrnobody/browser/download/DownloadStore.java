package com.mrnobody.browser.download;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The download list, on disk.
 *
 * <p>A download outlives the screen that started it and, after a pause, the
 * process itself. Keeping the list in memory would mean a paused film could
 * not be resumed after the app was swept away — so this is SQLite, and the
 * byte offset is written as it goes.
 */
public final class DownloadStore extends SQLiteOpenHelper {

    private static final String DB = "downloads.db";
    private static final int VERSION = 2;
    private static final String TABLE = "downloads";

    private static volatile DownloadStore instance;

    public static DownloadStore get(@NonNull Context context) {
        if (instance == null) {
            synchronized (DownloadStore.class) {
                if (instance == null) {
                    instance = new DownloadStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private DownloadStore(Context context) {
        super(context, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "url TEXT NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "mime TEXT,"
                + "user_agent TEXT,"
                + "referrer TEXT,"
                + "dest_uri TEXT,"
                + "dest_label TEXT,"
                + "total INTEGER NOT NULL DEFAULT -1,"
                + "bytes INTEGER NOT NULL DEFAULT 0,"
                + "status TEXT NOT NULL,"
                + "error TEXT,"
                + "etag TEXT,"
                + "resumable INTEGER NOT NULL DEFAULT 0,"
                + "risky_approved INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_downloads_status ON " + TABLE + "(status)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Never drop this table: partial downloads are user data.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE
                    + " ADD COLUMN risky_approved INTEGER NOT NULL DEFAULT 1");
        }
    }

    // ----------------------------------------------------------------- writes

    /** Insert a queued download and stamp the record with its new id. */
    public long insert(@NonNull DownloadRecord r) {
        long id = getWritableDatabase().insert(TABLE, null, values(r));
        r.id = id;
        return id;
    }

    public void update(@NonNull DownloadRecord r) {
        r.updatedAt = System.currentTimeMillis();
        getWritableDatabase().update(TABLE, values(r), "id=?", new String[]{String.valueOf(r.id)});
    }

    /**
     * Write only the moving parts. Called every progress tick, so it avoids
     * rewriting the URL and the name a few times a second.
     */
    public void updateProgress(long id, long bytes, long total) {
        ContentValues v = new ContentValues();
        v.put("bytes", bytes);
        v.put("total", total);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, v, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
    }

    /** Clear the whole list (Clear data → downloads). Files are not touched. */
    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    // ------------------------------------------------------------------ reads

    @Nullable
    public DownloadRecord find(long id) {
        try (Cursor c = getReadableDatabase().query(TABLE, null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c != null && c.moveToFirst() ? read(c) : null;
        }
    }

    /** Newest first: the thing you just started is the thing you want to see. */
    public List<DownloadRecord> all() {
        List<DownloadRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null,
                "created_at DESC")) {
            while (c != null && c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    /**
     * Downloads that claim to be running. After a process death none of them
     * are, which is what {@link DownloadEngine#reconcile} uses this to fix.
     */
    public List<DownloadRecord> active() {
        List<DownloadRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, "status IN (?,?)",
                new String[]{DownloadRecord.Status.RUNNING.name(),
                        DownloadRecord.Status.QUEUED.name()},
                null, null, "created_at ASC")) {
            while (c != null && c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    // ---------------------------------------------------------------- mapping

    private static ContentValues values(DownloadRecord r) {
        ContentValues v = new ContentValues();
        v.put("url", r.url);
        v.put("file_name", r.fileName);
        v.put("mime", r.mime);
        v.put("user_agent", r.userAgent);
        v.put("referrer", r.referrer);
        v.put("dest_uri", r.destUri);
        v.put("dest_label", r.destLabel);
        v.put("total", r.total);
        v.put("bytes", r.bytes);
        v.put("status", r.status.name());
        v.put("error", r.error);
        v.put("etag", r.etag);
        v.put("resumable", r.resumable ? 1 : 0);
        v.put("risky_approved", r.riskyApproved ? 1 : 0);
        v.put("created_at", r.createdAt);
        v.put("updated_at", r.updatedAt);
        return v;
    }

    private static DownloadRecord read(Cursor c) {
        DownloadRecord r = new DownloadRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.url = c.getString(c.getColumnIndexOrThrow("url"));
        r.fileName = c.getString(c.getColumnIndexOrThrow("file_name"));
        r.mime = c.getString(c.getColumnIndexOrThrow("mime"));
        r.userAgent = c.getString(c.getColumnIndexOrThrow("user_agent"));
        r.referrer = c.getString(c.getColumnIndexOrThrow("referrer"));
        r.destUri = c.getString(c.getColumnIndexOrThrow("dest_uri"));
        r.destLabel = c.getString(c.getColumnIndexOrThrow("dest_label"));
        r.total = c.getLong(c.getColumnIndexOrThrow("total"));
        r.bytes = c.getLong(c.getColumnIndexOrThrow("bytes"));
        r.status = DownloadRecord.Status.of(c.getString(c.getColumnIndexOrThrow("status")));
        r.error = c.getString(c.getColumnIndexOrThrow("error"));
        r.etag = c.getString(c.getColumnIndexOrThrow("etag"));
        r.resumable = c.getInt(c.getColumnIndexOrThrow("resumable")) == 1;
        r.riskyApproved = c.getInt(c.getColumnIndexOrThrow("risky_approved")) == 1;
        r.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        r.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return r;
    }
}
