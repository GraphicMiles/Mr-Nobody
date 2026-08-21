package com.mrnobody.browser.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import com.mrnobody.debug.ErrorLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The bundled Tor: Guardian Project's {@code org.torproject.jni.TorService},
 * started on demand when the user chose the Tor route and Orbot is not
 * running.
 *
 * <p><b>No compile-time dependency on tor-android.</b> The service is
 * addressed by class name and its broadcast contract by the string constants
 * it documents. That keeps the whole module compilable on the plain-javac
 * test harness (which has no AAR classpath), keeps the integration working
 * across tor-android versions, and means a build without the AAR simply
 * reports {@link #isBundled()} {@code false} — the Orbot path is unchanged.
 *
 * <p><b>Readiness has two signals.</b> TorService broadcasts
 * {@code ACTION_STATUS} with {@code EXTRA_STATUS=ON} at the first circuit
 * (sent both via LocalBroadcastManager and as a package-scoped broadcast);
 * and it binds SOCKS5 to {@code 127.0.0.1:9050} when that port is free —
 * which is exactly the port {@link OrbotTorRoute} already probes. The waiter
 * listens for the broadcast <em>and</em> re-probes the port, so a missed
 * broadcast can never wedge it. If 9050 was somehow taken by a non-SOCKS
 * squatter, TorService falls back to an auto port this route cannot see; the
 * result is an honest fail-closed refusal, recorded in the error log.
 */
public final class EmbeddedTor {

    /** The service bundled by info.guardianproject:tor-android. */
    public static final String SERVICE_CLASS = "org.torproject.jni.TorService";

    /** TorService's documented broadcast contract. */
    public static final String ACTION_STATUS = "org.torproject.android.intent.action.STATUS";
    public static final String EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS";

    private EmbeddedTor() {
    }

    /** True when the tor-android service is packaged into this build. */
    public static boolean isBundled() {
        try {
            // initialize=false: TorService's static initializer loads the
            // native tor library, which is not a price an availability check
            // should pay (and its failure mode is not an answer, it's a crash).
            Class.forName(SERVICE_CLASS, false, EmbeddedTor.class.getClassLoader());
            // TorService.onCreate's first act is LocalBroadcastManager, and
            // tor-android's POM declares no dependencies at all — a build
            // that forgot the androidx artifact would crash the whole app
            // the moment the service starts. Device-observed on 2026-08-21.
            // Missing dep = not bundled = honest Orbot-only refusal.
            Class.forName("androidx.localbroadcastmanager.content.LocalBroadcastManager",
                    false, EmbeddedTor.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Start the bundled Tor (idempotent — TorService ignores a duplicate
     * start) and wait up to {@code waitMs} for it to become ready. Returns
     * true when the SOCKS port is up; false when it is still bootstrapping
     * or failed, in which case the caller refuses the mode and the service
     * keeps bootstrapping for the user's retry.
     */
    public static boolean startAndAwait(Context context, long waitMs) {
        if (context == null || !isBundled()) return false;
        Context app = context.getApplicationContext();

        CountDownLatch ready = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                String status = intent.getStringExtra(EXTRA_STATUS);
                if (EmbeddedTorPolicy.statusMeansReady(status)) ready.countDown();
            }
        };

        boolean registered = registerStatusReceiver(app, receiver);
        Object localManager = registerLocalReceiver(app, receiver);
        try {
            app.startService(new Intent().setClassName(app, SERVICE_CLASS));
        } catch (Throwable t) {
            ErrorLog.record("embedded tor: could not start service: " + t);
            unregister(app, receiver, registered, localManager);
            return false;
        }

        try {
            long deadline = System.currentTimeMillis() + Math.max(0L, waitMs);
            while (System.currentTimeMillis() < deadline) {
                if (socksListening()) return true;
                if (ready.await(EmbeddedTorPolicy.PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    // Status ON: circuits are up; the port follows immediately.
                    return socksListening() || waitForPort(deadline);
                }
            }
            return socksListening();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return socksListening();
        } finally {
            unregister(app, receiver, registered, localManager);
        }
    }

    /** Stop the bundled Tor. Only called when leaving the Tor route. */
    public static void stop(Context context) {
        if (context == null || !isBundled()) return;
        try {
            Context app = context.getApplicationContext();
            app.stopService(new Intent().setClassName(app, SERVICE_CLASS));
        } catch (Throwable t) {
            ErrorLog.record("embedded tor: stop failed: " + t);
        }
    }

    // ------------------------------------------------------------- plumbing

    private static boolean waitForPort(long deadline) throws InterruptedException {
        while (System.currentTimeMillis() < deadline) {
            if (socksListening()) return true;
            Thread.sleep(EmbeddedTorPolicy.PROBE_INTERVAL_MS);
        }
        return socksListening();
    }

    /** The same loopback probe OrbotTorRoute trusts. */
    private static boolean socksListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(
                    OrbotTorRoute.HOST, OrbotTorRoute.PORT), 400);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The package-scoped system broadcast: NOT_EXPORTED on 33+. */
    private static boolean registerStatusReceiver(Context app, BroadcastReceiver receiver) {
        try {
            IntentFilter filter = new IntentFilter(ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            return true;
        } catch (Throwable t) {
            ErrorLog.record("embedded tor: receiver registration failed: " + t);
            return false;
        }
    }

    /**
     * TorService also announces through the deprecated LocalBroadcastManager;
     * subscribe by reflection so nothing here needs it on the classpath.
     * Returns the manager instance for unregistering, or null.
     */
    private static Object registerLocalReceiver(Context app, BroadcastReceiver receiver) {
        try {
            Class<?> lbm = Class.forName(
                    "androidx.localbroadcastmanager.content.LocalBroadcastManager");
            Object manager = lbm.getMethod("getInstance", Context.class).invoke(null, app);
            lbm.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class)
                    .invoke(manager, receiver, new IntentFilter(ACTION_STATUS));
            return manager;
        } catch (Throwable t) {
            return null; // the system broadcast and the port probe still cover us
        }
    }

    private static void unregister(Context app, BroadcastReceiver receiver,
                                   boolean registered, Object localManager) {
        if (registered) {
            try {
                app.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
                // Already unregistered — nothing to leak.
            }
        }
        if (localManager != null) {
            try {
                localManager.getClass()
                        .getMethod("unregisterReceiver", BroadcastReceiver.class)
                        .invoke(localManager, receiver);
            } catch (Throwable ignored) {
                // Best-effort; the receiver holds no context reference beyond this call.
            }
        }
    }
}
