package com.example.viewerguard;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PlayerLogger {
    private static final String TAG = "XXPlayer";
    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE_NAME = "player.log";
    private static final long MAX_LOG_SIZE_BYTES = 2L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    private static volatile File logFile;

    private PlayerLogger() {
    }

    public static void init(Context context) {
        synchronized (LOCK) {
            File base = context.getExternalFilesDir(null);
            if (base == null) {
                base = context.getFilesDir();
            }
            File dir = new File(base, LOG_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Failed to create log dir: " + dir.getAbsolutePath());
            }
            logFile = new File(dir, LOG_FILE_NAME);
            i("Logger", "log_path=" + logFile.getAbsolutePath());
        }
    }

    public static void i(String event, String msg) {
        write("I", event, msg, null);
    }

    public static void w(String event, String msg) {
        write("W", event, msg, null);
    }

    public static void e(String event, String msg, @Nullable Throwable t) {
        write("E", event, msg, t);
    }

    private static void write(String level, String event, String msg, @Nullable Throwable t) {
        String line = formatLine(level, event, msg, t);
        switch (level) {
            case "E":
                if (t != null) {
                    Log.e(TAG, "[" + event + "] " + msg, t);
                } else {
                    Log.e(TAG, "[" + event + "] " + msg);
                }
                break;
            case "W":
                Log.w(TAG, "[" + event + "] " + msg);
                break;
            default:
                Log.i(TAG, "[" + event + "] " + msg);
                break;
        }

        synchronized (LOCK) {
            if (logFile == null) {
                return;
            }
            try {
                rotateIfNeeded(logFile);
                FileWriter writer = new FileWriter(logFile, true);
                writer.write(line);
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String formatLine(String level, String event, String msg, @Nullable Throwable t) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder(256);
        sb.append(ts)
                .append(" ")
                .append(level)
                .append("/")
                .append(event)
                .append(" ")
                .append(msg);
        if (t != null) {
            sb.append(" ex=").append(t.getClass().getSimpleName()).append(":").append(t.getMessage());
        }
        sb.append("\n");
        return sb.toString();
    }

    private static void rotateIfNeeded(File file) {
        if (!file.exists()) {
            return;
        }
        if (file.length() < MAX_LOG_SIZE_BYTES) {
            return;
        }
        File old = new File(file.getParentFile(), LOG_FILE_NAME + ".old");
        if (old.exists()) {
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        file.renameTo(old);
    }
}
