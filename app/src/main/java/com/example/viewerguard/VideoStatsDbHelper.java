package com.example.viewerguard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

public class VideoStatsDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "video_stats.db";
    private static final int DB_VERSION = 2;

    private static final String TABLE_VIDEO_STATS = "video_stats";
    private static final String COL_VIDEO_MD5 = "video_md5";
    private static final String COL_VIDEO_PATH = "video_path";
    private static final String COL_VIDEO_NAME = "video_name";
    private static final String COL_PLAY_COUNT = "play_count";

    public VideoStatsDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_VIDEO_STATS + " ("
                + COL_VIDEO_MD5 + " TEXT PRIMARY KEY, "
                + COL_VIDEO_NAME + " TEXT, "
                + COL_VIDEO_PATH + " TEXT, "
                + COL_PLAY_COUNT + " INTEGER NOT NULL DEFAULT 0"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_VIDEO_STATS);
        onCreate(db);
    }

    public Map<String, Integer> queryAllPlayCounts() {
        Map<String, Integer> result = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_VIDEO_STATS,
                new String[]{COL_VIDEO_MD5, COL_PLAY_COUNT},
                null,
                null,
                null,
                null,
                null)) {
            while (c.moveToNext()) {
                String md5 = c.getString(0);
                int count = c.getInt(1);
                result.put(md5, count);
            }
        }
        return result;
    }

    public int incrementPlayCount(String videoMd5, String videoPath, String videoName) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int currentCount = 0;
            try (Cursor c = db.query(TABLE_VIDEO_STATS,
                    new String[]{COL_PLAY_COUNT},
                    COL_VIDEO_MD5 + "=?",
                    new String[]{videoMd5},
                    null,
                    null,
                    null)) {
                if (c.moveToFirst()) {
                    currentCount = c.getInt(0);
                }
            }

            int nextCount = currentCount + 1;
            ContentValues values = new ContentValues();
            values.put(COL_VIDEO_MD5, videoMd5);
            values.put(COL_VIDEO_PATH, videoPath);
            values.put(COL_VIDEO_NAME, videoName);
            values.put(COL_PLAY_COUNT, nextCount);
            db.insertWithOnConflict(TABLE_VIDEO_STATS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
            return nextCount;
        } finally {
            db.endTransaction();
        }
    }
}
