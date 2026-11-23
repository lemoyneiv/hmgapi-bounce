package dev.lemoyne.bounce;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import hmgapi.client.HmgApi;

/**
 * Receives Star-key ({@code ★}) presses ({@code dev.lemoyne.bounce.ACTION_BOUNCE})
 * and HMG-API {@code car_events} / {@code BOOT_COMPLETED} notifications for optional
 * silent standby-resume auto-bouncing.
 */
public final class BounceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (BounceDispatcher.ACTION_BOUNCE.equals(action)) {
            BounceDispatcher.triggerManualAsync(context, goAsync());
        } else if (HmgApi.ACTION_CAR_EVENT.equals(action)) {
            String ev = intent.getStringExtra(HmgApi.EXTRA_EVENT);
            if (HmgApi.EVENT_WAKE.equals(ev) || HmgApi.EVENT_UNPARKED.equals(ev)) {
                BounceDispatcher.handleAutoEventAsync(context, ev, goAsync());
            }
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            BounceDispatcher.handleAutoEventAsync(context, "boot", goAsync());
        }
    }
}
