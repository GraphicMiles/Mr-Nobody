package com.mrnobody.debug;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Captures a native crash so it is never invisible.
 *
 * <p>This app ships no crash reporter by design — the debug ⓘ panel is the only
 * channel a user has. But a Java uncaught exception (or an {@link OutOfMemoryError})
 * bypasses Flutter's {@code onError} hook entirely and takes the process down
 * with nothing written anywhere, which is exactly how "watch the price of
 * bitcoin" crashed silently and repeatedly. Installing a default uncaught
 * handler writes the stack to a file before the process dies; on the next
 * launch {@link #read} pulls it into the error log, so the ⓘ badge and its
 * copyable panel carry the crash.
 *
 * <p>The handler is deliberately tiny: it must run while the process is dying,
 * so it writes one small file and nothing else. It chains to the previous
 * handler (or exits) so normal crash behaviour is preserved.
 */
public final class CrashLog {

    private static final String FILE = "last_crash.txt";

    private CrashLog() {
    }

    /** Install the crash handler once, at app startup. */
    public static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                write(context, sw.toString());
                ErrorLog.record("app crashed: " + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage());
            } catch (Throwable ignored) {
                // The process is dying; failing to record must not mask the crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(1);
            }
        });
    }

    /** The last recorded crash, or null when there is none. Bounded on purpose:
     *  a pathological stack must not itself OOM the very read meant to report it. */
    private static final int MAX_CRASH_BYTES = 64 * 1024;

    public static String read(Context context) {
        try {
            File f = new File(context.getFilesDir(), FILE);
            if (!f.exists()) return null;
            int len = (int) Math.min(f.length(), MAX_CRASH_BYTES);
            byte[] bytes = new byte[len];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(bytes);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Forget the recorded crash (after it has been surfaced). */
    public static void clear(Context context) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), FILE).delete();
        } catch (Exception ignored) {
        }
    }

    private static void write(Context context, String stack) {
        try {
            File f = new File(context.getFilesDir(), FILE);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(stack.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
