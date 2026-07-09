package com.firesky.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FireSkyDiagnostics.start(getApplicationContext());
    }
}

/**
 * Dependency-free crash and main-thread stall reporter. Reports are written
 * locally first, then sent on a later launch, so an offline crash is not lost.
 * The payload deliberately excludes location, account, and stack contents.
 */
final class FireSkyDiagnostics {
    private static final String PREFS = "firesky_diagnostics";
    private static final String PENDING = "pending_event";
    private static final String INSTALLATION = "installation";
    private static final String ENDPOINT = "https://fireskychase.pages.dev/api/telemetry";
    private static final long ANR_THRESHOLD_MS = 6000;
    private static boolean started = false;

    private FireSkyDiagnostics() {}

    static synchronized void start(Context context) {
        if (started) return;
        started = true;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        flush(app, prefs);

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            persist(prefs, "native_crash", throwable == null ? "Unknown" : throwable.getClass().getSimpleName());
            if (previous != null) previous.uncaughtException(thread, throwable);
        });

        Handler mainHandler = new Handler(Looper.getMainLooper());
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        final long[] lastMainPulse = { SystemClock.uptimeMillis() };
        final boolean[] reported = { false };
        Runnable pulse = new Runnable() {
            @Override public void run() {
                lastMainPulse[0] = SystemClock.uptimeMillis();
                reported[0] = false;
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(pulse);
        watchdog.scheduleAtFixedRate(() -> {
            long stalledFor = SystemClock.uptimeMillis() - lastMainPulse[0];
            if (stalledFor >= ANR_THRESHOLD_MS && !reported[0]) {
                reported[0] = true;
                persist(prefs, "native_anr", "main_thread_stall_" + stalledFor + "ms");
                flush(app, prefs);
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private static void persist(SharedPreferences prefs, String event, String reason) {
        try {
            JSONObject body = new JSONObject();
            body.put("event", event);
            body.put("at", System.currentTimeMillis());
            body.put("installation", installationId(prefs));
            body.put("platform", "android-native");
            JSONObject detail = new JSONObject();
            detail.put("reason", reason.substring(0, Math.min(reason.length(), 160)));
            body.put("detail", detail);
            prefs.edit().putString(PENDING, body.toString()).apply();
        } catch (Exception ignored) {
            // Last-resort diagnostics must never crash the app again.
        }
    }

    private static String installationId(SharedPreferences prefs) {
        String existing = prefs.getString(INSTALLATION, null);
        if (existing != null) return existing;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(INSTALLATION, created).apply();
        return created;
    }

    private static void flush(Context context, SharedPreferences prefs) {
        String payload = prefs.getString(PENDING, null);
        if (payload == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    prefs.edit().remove(PENDING).apply();
                }
            } catch (Exception ignored) {
                // Retain the single most recent native report for the next launch.
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }
}
