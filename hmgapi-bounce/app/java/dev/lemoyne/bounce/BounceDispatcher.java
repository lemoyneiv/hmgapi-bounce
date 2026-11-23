package dev.lemoyne.bounce;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;

import hmgapi.client.HmgApi;

/**
 * Non-blocking client dispatcher bridging {@link BounceActivity} (Home Screen tile)
 * and {@link BounceReceiver} (Star-key &amp; {@code car_events}) to {@code hmgapid}'s
 * on-demand root services ({@code bounce}, {@code bounce-silent}, {@code watchdog}).
 */
final class BounceDispatcher {

    static final String TAG = "HmgBounce";
    static final String ACTION_BOUNCE = "dev.lemoyne.bounce.ACTION_BOUNCE";

    private BounceDispatcher() {}

    static void triggerManualAsync(final Context context, final BroadcastReceiver.PendingResult pending) {
        final Context appCtx = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HmgApi api = HmgApi.connect().timeout(4000);
                    if (!api.available()) {
                        if (appCtx != null) {
                            HmgApi.toast(appCtx, "HMG-API service unavailable");
                        }
                        return;
                    }
                    if (isAnyBounceRunning(api)) {
                        if (appCtx != null) {
                            HmgApi.toast(appCtx, "Bounce already in progress…");
                        }
                        return;
                    }
                    api.startService("bounce");
                } catch (Throwable ex) {
                    Log.e(TAG, "[hmgapi-bounce] manual trigger error", ex);
                    if (appCtx != null) {
                        HmgApi.toast(appCtx, "Bounce failed to start");
                    }
                } finally {
                    if (pending != null) {
                        try {
                            pending.finish();
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }, "hmgapi-bounce-trigger");
        t.setDaemon(true);
        t.start();
    }

    static void handleAutoEventAsync(
            final Context context,
            final String eventType,
            final BroadcastReceiver.PendingResult pending) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HmgApi api = HmgApi.connect().timeout(4000);
                    if (!api.available()) return;
                    JSONObject settings = api.settings();
                    String mode = settings != null ? settings.optString("auto_bounce_mode", "off") : "off";
                    if ("off".equals(mode) || mode.isEmpty()) {
                        return;
                    }
                    List<HmgApi.Service> svcs = api.services();
                    if (isRunning(svcs, "bounce") || isRunning(svcs, "bounce-silent")) {
                        return;
                    }
                    if ("every_wake".equals(mode)) {
                        if (HmgApi.EVENT_WAKE.equals(eventType) || HmgApi.EVENT_UNPARKED.equals(eventType)) {
                            api.startService("bounce-silent");
                        }
                    } else if ("watchdog".equals(mode)) {
                        if (!isRunning(svcs, "watchdog")) {
                            api.startService("watchdog");
                        }
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, "[hmgapi-bounce] auto-event error (" + eventType + ")", ex);
                } finally {
                    if (pending != null) {
                        try {
                            pending.finish();
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }, "hmgapi-bounce-auto");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isAnyBounceRunning(HmgApi api) {
        try {
            List<HmgApi.Service> svcs = api.services();
            return isRunning(svcs, "bounce") || isRunning(svcs, "bounce-silent");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRunning(List<HmgApi.Service> svcs, String name) {
        if (svcs == null) return false;
        for (int i = 0; i < svcs.size(); i++) {
            HmgApi.Service s = svcs.get(i);
            if (s != null && name.equals(s.name) && (s.pid > 0 || "running".equals(s.state))) {
                return true;
            }
        }
        return false;
    }
}
