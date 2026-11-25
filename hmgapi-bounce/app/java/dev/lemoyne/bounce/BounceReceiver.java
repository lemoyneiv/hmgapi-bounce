package dev.lemoyne.bounce;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

import hmgapi.client.HmgApi;

/**
 * Receives Star-key ({@code ★}) presses ({@code dev.lemoyne.bounce.ACTION_BOUNCE})
 * and HMG-API {@code car_events} / {@code BOOT_COMPLETED} notifications for optional
 * silent standby-resume auto-bouncing.
 */
public final class BounceReceiver extends BroadcastReceiver {

    private static final String PUBLIC_STATE_PATH = "/data/hmgapi/public/state.json";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (BounceDispatcher.ACTION_BOUNCE.equals(action)) {
            BounceDispatcher.triggerManualAsync(context, goAsync());
        } else if (HmgApi.ACTION_CAR_EVENT.equals(action)) {
            String ev = intent.getStringExtra(HmgApi.EXTRA_EVENT);
            if (HmgApi.EVENT_WAKE.equals(ev) || HmgApi.EVENT_UNPARKED.equals(ev)) {
                if (BouncePolicy.shouldSkipAutoEventForPublicState(ev, readPublicStateJsonQuiet())) {
                    return;
                }
                BounceDispatcher.handleAutoEventAsync(context, ev, goAsync());
            }
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (BouncePolicy.shouldSkipAutoEventForPublicState("boot", readPublicStateJsonQuiet())) {
                return;
            }
            BounceDispatcher.handleAutoEventAsync(context, "boot", goAsync());
        }
    }

    private static String readPublicStateJsonQuiet() {
        File f = new File(PUBLIC_STATE_PATH);
        if (!f.isFile()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 262_144) return "";
            }
            return out.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {}
            }
        }
    }
}
