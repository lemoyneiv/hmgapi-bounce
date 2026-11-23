package dev.lemoyne.bounce;

import android.app.Activity;
import android.os.Bundle;

/**
 * Zero-UI translucent Home Screen tile trampoline.
 *
 * <p>Dispatches {@code bounce} immediately and finishes in {@code onCreate()} with
 * animations suppressed so tapping "Bounce Connection" on the Gen5W Home Screen
 * keeps the driver on the Home Screen with zero window flash.</p>
 */
public final class BounceActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        BounceDispatcher.triggerManualAsync(this, null);
        finish();
        overridePendingTransition(0, 0);
    }
}
