package dev.lemoyne.bounce;

import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-demand root {@code app_process} worker for {@code hmgapi-bounce}.
 *
 * <p>Consumes zero RAM and zero CPU while idle: spawned by {@code hmgapid} only when
 * the driver taps the Home Screen tile, presses the {@code ★} button, or wakes from
 * standby with {@code auto_bounce_mode} enabled.</p>
 */
public final class BounceRunner {

    private static final String TAG = "HmgBounce";
    private static final String HMG_TAG = "HMG-API";
    private static final String MOD_ID = "hmgapi-bounce";

    private static final File BOOT_ID_FILE = new File("/proc/sys/kernel/random/boot_id");
    private static final File STATE_JSON_FILE = new File("/data/hmgapi/public/state.json");
    private static final File MOD_SETTINGS_FILE = new File("/data/hmgapi/settings/hmgapi-bounce.json");
    private static final File WIFI_JSON_FILE = new File("/data/hmgapi/public/wifi.json");
    private static final File KEEPAWAKE_FILE = new File("/data/hmgapi/public/keepawake.json");
    private static final File OPENLINK_KEEPAWAKE_FILE = new File("/data/hmgapi/public/openlink-keepawake.json");
    private static final File PROJECTION_RESUME_FILE = new File("/data/hmgapi/public/projection-resume.json");
    private static final File BOUNCE_LAST_FILE = new File("/data/hmgapi/public/bounce-last.json");

    /** Volatile tmpfs markers in /dev (automatically cleared by kernel across cold boots). */
    private static final File BT_MARKER_FILE = new File("/dev/.hmgapi-bounce-bt");
    private static final File MUTE_MARKER_FILE = new File("/dev/.hmgapi-bounce-mute");
    private static final File CONFIRM_MARKER_FILE = new File("/dev/.hmgapi-bounce-confirm");

    private static final Map<String, Object> cachedProxies = new HashMap<String, Object>();
    private static final Map<String, IBinder> cachedBinders = new HashMap<String, IBinder>();

    private static volatile boolean holdingMute = false;
    private static volatile boolean holdingBtOff = false;

    private BounceRunner() {}

    public static void main(String[] args) {
        String mode = (args != null && args.length > 0 && args[0] != null) ? args[0].trim() : "manual";
        installSafetyShutdownHook();

        try {
            waitForBootCompleted(15_000L);
            if ("cleanup".equals(mode)) {
                runBootCleanup();
                return;
            }
            if ("watchdog".equals(mode)) {
                runSmartWatchdog();
                return;
            }
            boolean manual = !"silent".equals(mode);
            executeBounce(manual, manual ? "manual" : "every_wake");
        } catch (Throwable t) {
            logErr("unhandled error in mode=" + mode, t);
            emergencyRestoreIfNeeded();
        }
    }

    private static void installSafetyShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    emergencyRestoreIfNeeded();
                }
            }, "hmgapi-bounce-CleanupHook"));
        } catch (Throwable ignored) {}
    }

    private static void emergencyRestoreIfNeeded() {
        try {
            if (holdingBtOff || BT_MARKER_FILE.isFile()) {
                holdingBtOff = false;
                setBluetoothEnabledVolatile(true);
            }
        } catch (Throwable ignored) {}
        try {
            if (holdingMute || MUTE_MARKER_FILE.isFile()) {
                holdingMute = false;
                invokeNoArgBinder("com.hkmc.micom.audiosetup", "unMuteAV");
                MUTE_MARKER_FILE.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static void runBootCleanup() {
        CONFIRM_MARKER_FILE.delete();
        if (MUTE_MARKER_FILE.isFile()) {
            invokeNoArgBinder("com.hkmc.micom.audiosetup", "unMuteAV");
            MUTE_MARKER_FILE.delete();
            log("cleared leftover mute marker at boot");
        }
        if (BT_MARKER_FILE.isFile()) {
            int btSt = invokeIntBinder("bluetooth_manager", "getState", -1);
            if (btSt == BouncePolicy.BT_STATE_ON || btSt == BouncePolicy.BT_STATE_TURNING_ON) {
                BT_MARKER_FILE.delete();
            } else {
                // Defer until stock boot settles if ever left on tmpfs across soft restart
                long rem = BouncePolicy.LEASE_FRESHNESS_MS - SystemClock.elapsedRealtime();
                if (rem > 0L) sleepQuietly(Math.min(rem, 15_000L));
                setBluetoothEnabledVolatile(true);
                log("recovered Bluetooth from tmpfs marker");
            }
        }
    }

    private static void runSmartWatchdog() {
        long start = SystemClock.elapsedRealtime();
        // 1. Wait up to 8s for IG ON
        while (!isCarIgnitionOn() && (SystemClock.elapsedRealtime() - start) < 8_000L) {
            sleepQuietly(400L);
        }
        if (!isCarIgnitionOn()) {
            return;
        }

        // 2. Wait up to 20s for Bluetooth STATE_ON (12)
        long btWaitStart = SystemClock.elapsedRealtime();
        while (readBluetoothState() != BouncePolicy.BT_STATE_ON
                && (SystemClock.elapsedRealtime() - btWaitStart) < 20_000L) {
            if (!isCarIgnitionOn()) return;
            sleepQuietly(500L);
        }
        if (readBluetoothState() != BouncePolicy.BT_STATE_ON) {
            return;
        }

        // 3. Observe for 15s: exit immediately if CarPlay or Android Auto connects naturally
        long watchStart = SystemClock.elapsedRealtime();
        while ((SystemClock.elapsedRealtime() - watchStart) < BouncePolicy.WATCHDOG_DELAY_MS) {
            if (!isCarIgnitionOn()) return;
            if (isCarPlayConnected() || isAndroidAutoConnected()) {
                log("WATCHDOG_OK projection connected naturally within "
                        + (SystemClock.elapsedRealtime() - watchStart) + "ms");
                return;
            }
            sleepQuietly(500L);
        }

        boolean ignOn = isCarIgnitionOn();
        int btSt = readBluetoothState();
        String hfpMac = invokeStringBinder("phoneprojection", "getBtHfpConnectDevice");
        String connMac = invokeStringBinder("phoneprojection", "getConnectedDeviceBtAddress");
        boolean cpUp = isCarPlayConnected();
        boolean aaUp = isAndroidAutoConnected();
        boolean callActive = isAnyCallActive();
        boolean homeHold = isHomeWifiHoldOnDevice(SystemClock.elapsedRealtime());

        if (BouncePolicy.shouldWatchdogTriggerBounce(
                ignOn, btSt, hfpMac, connMac, cpUp, aaUp, callActive, homeHold)) {
            log("WATCHDOG_TRIGGER BT connected (" + BouncePolicy.redactMac(!hfpMac.isEmpty() ? hfpMac : connMac)
                    + ") without CarPlay/AA after 15s — running silent bounce");
            executeBounce(false, "watchdog");
        }
    }

    private static void executeBounce(boolean manual, String trigger) {
        long t0 = SystemClock.elapsedRealtime();
        long wallStart = System.currentTimeMillis();
        String bootId = readSmallUtf8(BOOT_ID_FILE).trim();

        boolean ignOn = isCarIgnitionOn();
        boolean callActive = isAnyCallActive();
        boolean homeHold = isHomeWifiHoldOnDevice(t0);
        long confirmStamp = readConfirmStamp();

        BouncePolicy.GuardDecision guard = BouncePolicy.evaluateGuard(
                manual, callActive, ignOn, homeHold, confirmStamp, t0);

        switch (guard) {
            case REFUSE_CALL_CONFIRM:
                writeConfirmStamp(t0);
                sendToastAsync("Phone call active — tap again within 5s to force");
                log("BOUNCE_REFUSED reason=call_active_confirm");
                return;
            case REFUSE_HOME_WIFI_CONFIRM:
                writeConfirmStamp(t0);
                sendToastAsync("Home Wi-Fi active — tap again within 5s to force");
                log("BOUNCE_REFUSED reason=home_wifi_confirm");
                return;
            case SKIP_SILENT_CALL:
                log("BOUNCE_SKIPPED reason=silent_call_active trigger=" + trigger);
                return;
            case SKIP_SILENT_HOME_WIFI:
                log("BOUNCE_SKIPPED reason=silent_home_wifi trigger=" + trigger);
                return;
            case PROCEED:
            case PROCEED_FORCED:
                CONFIRM_MARKER_FILE.delete();
                break;
        }

        boolean forced = (guard == BouncePolicy.GuardDecision.PROCEED_FORCED);
        boolean resetApps = readResetProjectionAppsSetting();

        if (manual) {
            sendToastAsync("Bouncing Wi-Fi & Bluetooth…");
            healStalledHomeWifiIfNeeded();
        }

        // ---- Phase 1: Snapshot pre-bounce session & send protocol-clean PPService disconnect ----
        int preMode = invokeIntBinder("com.hkmc.micom.mode", "getCurrentMode", -1);
        int preAud = invokeIntBinder("com.hkmc.micom.audiosetup", "getAudioMode", -1);
        int userMute = invokeBooleanBinder("com.hkmc.micom.audiosetup", "getUserMuteState");
        String preAaMac = BouncePolicy.normalizeBtMac(invokeStringBinder("phoneprojection", "getAAConnectedBTAddress"));
        String preCpMac = BouncePolicy.normalizeBtMac(invokeStringBinder("phoneprojection", "getCPConnectedBTAddress"));
        String preHfpMac = BouncePolicy.normalizeBtMac(invokeStringBinder("phoneprojection", "getBtHfpConnectDevice"));
        String preConnMac = BouncePolicy.normalizeBtMac(invokeStringBinder("phoneprojection", "getConnectedDeviceBtAddress"));

        if (!preCpMac.isEmpty() && preMode != BouncePolicy.MODE_CARPLAY) {
            preMode = BouncePolicy.MODE_CARPLAY;
        } else if (!preAaMac.isEmpty() && preMode != BouncePolicy.MODE_ANDROID_AUTO) {
            preMode = BouncePolicy.MODE_ANDROID_AUTO;
        }

        boolean hadAa = !preAaMac.isEmpty() || preMode == BouncePolicy.MODE_ANDROID_AUTO;
        boolean hadCp = !preCpMac.isEmpty() || preMode == BouncePolicy.MODE_CARPLAY;
        boolean holdMute = BouncePolicy.shouldHoldHardwareMute(preMode, preAud, userMute);
        String initTarget = BouncePolicy.selectReconnectNudgeTarget("", "", preAaMac, preCpMac, preHfpMac, preConnMac);

        log("BOUNCE_START trigger=" + trigger
                + " forced=" + forced
                + " resetApps=" + resetApps
                + " holdMute=" + holdMute
                + " preMode=" + preMode
                + " preAud=" + preAud
                + " target=" + BouncePolicy.redactMac(initTarget));

        String discDetail = disconnectProjectionCleanly(preAaMac, preCpMac);
        sleepQuietly(BouncePolicy.DISCONNECT_GRACE_MS);

        // ---- Phase 2: Hold mute (if appropriate) & bring Bluetooth + Wi-Fi SoftAP/Client DOWN ----
        if (holdMute) {
            writeSmallUtf8(MUTE_MARKER_FILE, bootId + "\n", true);
            holdingMute = true;
            invokeNoArgBinder("com.hkmc.micom.audiosetup", "muteAV");
        }

        long downStart = SystemClock.elapsedRealtime();
        setBluetoothEnabledVolatile(false);
        disableClientWifiAndSoftAp(ignOn || forced);

        while ((SystemClock.elapsedRealtime() - downStart) < BouncePolicy.RADIO_DOWN_TIMEOUT_MS) {
            int btSt = readBluetoothState();
            int apSt = invokeIntBinder("wifi", "getWifiApEnabledState", BouncePolicy.WIFI_AP_STATE_DISABLED);
            if (btSt == BouncePolicy.BT_STATE_OFF && apSt == BouncePolicy.WIFI_AP_STATE_DISABLED) {
                break;
            }
            sleepQuietly(120L);
        }
        long downMs = SystemClock.elapsedRealtime() - downStart;

        // ---- Phase 3: Refresh projection.sink / dipo workers strictly while Bluetooth is OFF ----
        int refreshedWorkers = 0;
        if (resetApps && readBluetoothState() == BouncePolicy.BT_STATE_OFF) {
            refreshedWorkers = sigtermAllowlistedWorkers(hadAa, hadCp, forced);
            if (refreshedWorkers > 0) {
                sleepQuietly(BouncePolicy.WORKER_SETTLE_MS);
            }
        }

        // Ensure WifiStateMachine has completely exited WIFI_AP_STATE_DISABLING (10) after
        // GalReceiver/DiPO sockets closed before requesting Phase 4 SoftAP re-enable!
        long apDrainStart = SystemClock.elapsedRealtime();
        while ((SystemClock.elapsedRealtime() - apDrainStart) < BouncePolicy.SOFTAP_DISABLING_DRAIN_MS) {
            int apSt = invokeIntBinder("wifi", "getWifiApEnabledState", BouncePolicy.WIFI_AP_STATE_DISABLED);
            if (BouncePolicy.isSoftApReadyForReenable(apSt)) {
                break;
            }
            sleepQuietly(120L);
        }

        // ---- Phase 4: Bring Mode 2 (5 GHz Connectivity) SoftAP UP FIRST ----
        long apStart = SystemClock.elapsedRealtime();
        enableConnectivitySoftAp();
        while ((SystemClock.elapsedRealtime() - apStart) < BouncePolicy.SOFTAP_UP_TIMEOUT_MS) {
            int apSt = invokeIntBinder("wifi", "getWifiApEnabledState", -1);
            if (apSt == BouncePolicy.WIFI_AP_STATE_ENABLED) {
                break;
            }
            sleepQuietly(120L);
        }
        long apUpMs = SystemClock.elapsedRealtime() - apStart;

        // ---- Phase 5: Publish FocusGuard projection-resume lease & bring Bluetooth UP ----
        long resumeNow = SystemClock.elapsedRealtime();
        publishProjectionResumeLease(bootId, preMode, initTarget, resumeNow);

        long btStart = SystemClock.elapsedRealtime();
        setBluetoothEnabledVolatile(true);
        while ((SystemClock.elapsedRealtime() - btStart) < BouncePolicy.BT_UP_TIMEOUT_MS) {
            if (readBluetoothState() == BouncePolicy.BT_STATE_ON) {
                break;
            }
            sleepQuietly(120L);
        }
        long btUpMs = SystemClock.elapsedRealtime() - btStart;
        long radiosReadyMs = SystemClock.elapsedRealtime() - t0;

        if (manual) {
            sendToastAsync("Ready — reconnecting phone");
        }

        // ---- Phase 6: Multi-phone reconnect watch, +6s nudge fallback & focus handoff ----
        long watchStart = SystemClock.elapsedRealtime();
        boolean nudged = false;
        String nudgeRes = "none";
        String outcome = "RADIOS_READY_NO_PHONE";
        long projUpMs = -1L;

        while ((SystemClock.elapsedRealtime() - watchStart) < BouncePolicy.RECONNECT_WATCH_MAX_MS) {
            if (holdingMute) {
                int curM = invokeIntBinder("com.hkmc.micom.mode", "getCurrentMode", -1);
                if (curM != BouncePolicy.MODE_CARPLAY && curM != BouncePolicy.MODE_ANDROID_AUTO) {
                    invokeNoArgBinder("com.hkmc.micom.audiosetup", "muteAV");
                }
            }

            boolean cpUp = isCarPlayConnected();
            boolean aaUp = isAndroidAutoConnected();
            if (cpUp || aaUp) {
                projUpMs = SystemClock.elapsedRealtime() - btStart;
                outcome = cpUp ? "CONNECTED_CARPLAY" : "CONNECTED_ANDROID_AUTO";
                releaseMuteIfHeld();
                handoffProjectionFocusWithCameraDeferral(cpUp, aaUp);
                break;
            }

            long sinceBtUp = SystemClock.elapsedRealtime() - watchStart;
            if (!nudged && sinceBtUp >= BouncePolicy.RECONNECT_NUDGE_DELAY_MS) {
                nudged = true;
                String liveHfp = invokeStringBinder("phoneprojection", "getBtHfpConnectDevice");
                String liveConn = invokeStringBinder("phoneprojection", "getConnectedDeviceBtAddress");
                String targetMac = BouncePolicy.selectReconnectNudgeTarget(
                        liveHfp, liveConn, preAaMac, preCpMac, preHfpMac, preConnMac);
                if (!targetMac.isEmpty()) {
                    nudgeRes = sendConnectPpNudge(targetMac, preAaMac, preCpMac, preMode);
                }
            }
            sleepQuietly(350L);
        }

        releaseMuteIfHeld();
        long totalMs = SystemClock.elapsedRealtime() - t0;

        writeLastResultJson(wallStart, trigger, outcome, discDetail, refreshedWorkers,
                downMs, apUpMs, btUpMs, radiosReadyMs, projUpMs, totalMs, nudgeRes);

        log("BOUNCE_DONE trigger=" + trigger
                + " outcome=" + outcome
                + " workers=" + refreshedWorkers
                + " downMs=" + downMs
                + " apUpMs=" + apUpMs
                + " btUpMs=" + btUpMs
                + " radiosMs=" + radiosReadyMs
                + " projUpMs=" + projUpMs
                + " nudge=" + nudgeRes
                + " totalMs=" + totalMs);
    }

    private static void handoffProjectionFocusWithCameraDeferral(boolean cpUp, boolean aaUp) {
        long deferStart = SystemClock.elapsedRealtime();
        while ((SystemClock.elapsedRealtime() - deferStart) < 15_000L) {
            int curMode = invokeIntBinder("com.hkmc.micom.mode", "getCurrentMode", -1);
            boolean cam = isAnyCameraCurrentlyActive();
            String decision = BouncePolicy.decideFocusShortcut(cpUp, aaUp, curMode, cam);
            if (decision.isEmpty()) {
                return;
            }
            if (!BouncePolicy.FOCUS_DEFER_CAMERA.equals(decision)) {
                sendActionBroadcast(decision);
                log("dispatched projection focus shortcut -> " + decision + " (prevMode=" + curMode + ")");
                return;
            }
            sleepQuietly(500L);
        }
    }

    private static void releaseMuteIfHeld() {
        if (!holdingMute && !MUTE_MARKER_FILE.isFile()) return;
        sleepQuietly(120L);
        invokeNoArgBinder("com.hkmc.micom.audiosetup", "unMuteAV");
        holdingMute = false;
        MUTE_MARKER_FILE.delete();
    }

    private static String disconnectProjectionCleanly(String aaMac, String cpMac) {
        StringBuilder sb = new StringBuilder(64);
        try {
            Object pp = getProxy("phoneprojection");
            if (pp == null) return "no-pp";
            Class<?> cls = pp.getClass();
            if (!aaMac.isEmpty()) {
                try {
                    cls.getMethod("disconnectPP", int.class, String.class, String.class, int.class)
                            .invoke(pp, Integer.valueOf(BouncePolicy.PP_TYPE_ANDROID_AUTO), aaMac, "", Integer.valueOf(1));
                    sb.append("discAA ");
                } catch (Throwable ignored) {}
                try {
                    cls.getMethod("onWirelessDisconnected", int.class, String.class)
                            .invoke(pp, Integer.valueOf(BouncePolicy.PP_TYPE_ANDROID_AUTO), aaMac);
                } catch (Throwable ignored) {}
            }
            if (!cpMac.isEmpty()) {
                try {
                    cls.getMethod("disconnectPP", int.class, String.class, String.class, int.class)
                            .invoke(pp, Integer.valueOf(BouncePolicy.PP_TYPE_CARPLAY), cpMac, "", Integer.valueOf(1));
                    sb.append("discCP ");
                } catch (Throwable ignored) {}
                try {
                    cls.getMethod("onWirelessDisconnected", int.class, String.class)
                            .invoke(pp, Integer.valueOf(BouncePolicy.PP_TYPE_CARPLAY), cpMac);
                } catch (Throwable ignored) {}
            }
            try {
                cls.getMethod("disconnectWirelessAll").invoke(pp);
                sb.append("discAll");
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        String res = sb.toString().trim();
        return res.isEmpty() ? "idle" : res.replace(' ', '+');
    }

    private static String sendConnectPpNudge(String targetMac, String preAaMac, String preCpMac, int preMode) {
        try {
            Object pp = getProxy("phoneprojection");
            if (pp == null) return "no-pp";
            Class<?> cls = pp.getClass();
            boolean cpAvail = false;
            int wlType = 0;
            try {
                Object r = cls.getMethod("isCPAutoConnectionAvailableDevice", String.class).invoke(pp, targetMac);
                cpAvail = Boolean.TRUE.equals(r);
            } catch (Throwable ignored) {}
            try {
                Object r = cls.getMethod("getWLDeviceTransferType", String.class, String.class).invoke(pp, targetMac, "");
                if (r instanceof Integer) wlType = ((Integer) r).intValue();
            } catch (Throwable ignored) {}

            int type = BouncePolicy.selectProjectionTypeForNudge(
                    targetMac, cpAvail, wlType, preAaMac, preCpMac, preMode);
            if (type <= 0) return "no-type";
            cls.getMethod("connectPP", int.class, String.class, String.class, int.class)
                    .invoke(pp, Integer.valueOf(type), targetMac, "", Integer.valueOf(1));
            return "connectPP(" + type + "," + BouncePolicy.redactMac(targetMac) + ")";
        } catch (Throwable t) {
            return "err";
        }
    }

    private static void setBluetoothEnabledVolatile(boolean enable) {
        try {
            Object bt = getProxy("bluetooth_manager");
            if (bt == null) return;
            Class<?> cls = bt.getClass();
            if (!enable) {
                writeSmallUtf8(BT_MARKER_FILE, readSmallUtf8(BOOT_ID_FILE).trim() + "\n", true);
                holdingBtOff = true;
                // Strictly persist=false (Boolean.FALSE) so Settings.Global.BLUETOOTH_ON is NEVER set to 0!
                boolean done = false;
                try {
                    cls.getMethod("disable", boolean.class).invoke(bt, Boolean.FALSE);
                    done = true;
                } catch (NoSuchMethodException ignored) {}
                if (!done) {
                    cls.getMethod("disable", String.class, boolean.class).invoke(bt, "android", Boolean.FALSE);
                }
            } else {
                holdingBtOff = false;
                BT_MARKER_FILE.delete();
                int btSt = readBluetoothState();
                if (!BouncePolicy.shouldInvokeBluetoothEnable(btSt)) {
                    return;
                }
                boolean done = false;
                try {
                    cls.getMethod("enable").invoke(bt);
                    done = true;
                } catch (NoSuchMethodException ignored) {}
                if (!done) {
                    cls.getMethod("enable", String.class).invoke(bt, "android");
                }
            }
        } catch (Throwable t) {
            logErr("setBluetoothEnabledVolatile(" + enable + ")", t);
        }
    }

    private static void disableClientWifiAndSoftAp(boolean clearClientWifi) {
        try {
            Object wifi = getProxy("wifi");
            if (wifi == null) return;
            Class<?> cls = wifi.getClass();
            if (clearClientWifi) {
                int wifiSt = invokeIntBinder("wifi", "getWifiEnabledState", 1);
                if (wifiSt == 2 || wifiSt == 3) {
                    boolean done = false;
                    try {
                        cls.getMethod("setWifiEnabled", boolean.class).invoke(wifi, Boolean.FALSE);
                        done = true;
                    } catch (NoSuchMethodException ignored) {}
                    if (!done) {
                        try {
                            cls.getMethod("setWifiEnabled", String.class, boolean.class)
                                    .invoke(wifi, "android", Boolean.FALSE);
                        } catch (Throwable ignored) {}
                    }
                }
            }
            // Pass null config first so WifiApConfigStore keeps ConnectivitySSID / ConnectivityPSK intact
            boolean offDone = false;
            try {
                Method m = cls.getMethod("setWifiApEnabledForConnectivity", WifiConfiguration.class, boolean.class);
                m.invoke(wifi, null, Boolean.FALSE);
                offDone = true;
            } catch (NoSuchMethodException ignored) {}
            if (!offDone) {
                cls.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                        .invoke(wifi, null, Boolean.FALSE);
            }
        } catch (Throwable t) {
            logErr("disableClientWifiAndSoftAp", t);
        }
    }

    private static void enableConnectivitySoftAp() {
        try {
            Object wifi = getProxy("wifi");
            if (wifi == null) return;
            Class<?> cls = wifi.getClass();
            boolean ok = false;
            try {
                Method m = cls.getMethod("setWifiApEnabledForConnectivity", WifiConfiguration.class, boolean.class);
                Object r = m.invoke(wifi, null, Boolean.TRUE);
                ok = !(r instanceof Boolean) || ((Boolean) r).booleanValue();
                if (!ok) {
                    Object cfg = cls.getMethod("getWifiApConfiguration").invoke(wifi);
                    if (cfg instanceof WifiConfiguration) {
                        m.invoke(wifi, cfg, Boolean.TRUE);
                        ok = true;
                    }
                }
            } catch (NoSuchMethodException ignored) {}
            if (!ok) {
                cls.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)
                        .invoke(wifi, null, Boolean.TRUE);
            }
        } catch (Throwable t) {
            logErr("enableConnectivitySoftAp", t);
        }
    }

    private static int sigtermAllowlistedWorkers(boolean hadAa, boolean hadCp, boolean forced) {
        int count = 0;
        File proc = new File("/proc");
        File[] dirs = proc.listFiles();
        if (dirs == null) return 0;
        int myPid = android.os.Process.myPid();
        List<Integer> targets = new ArrayList<Integer>(4);
        for (int i = 0; i < dirs.length; i++) {
            File d = dirs[i];
            String name = d.getName();
            if (name.isEmpty() || name.charAt(0) < '1' || name.charAt(0) > '9') continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 1 || pid == myPid) continue;
            String cmd = readProcCmdline(new File(d, "cmdline"));
            if (BouncePolicy.shouldRefreshWorker(cmd, hadAa, hadCp, forced)) {
                targets.add(Integer.valueOf(pid));
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            int pid = targets.get(i).intValue();
            try {
                android.os.Process.sendSignal(pid, 15 /* SIGTERM */);
                count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    /**
     * Self-heals custom HomeWifiDaemon (@hmgapi-wifi) builds during manual bounces if hmgapid
     * reports hmgapi/wifi running while @hmgapi-wifi accepts connections without answering in 800ms.
     * Pure no-op on stock Open-HMG/HMG-API (where state/wifi.sh is a shell loop without HomeWifiDaemon).
     */
    private static void healStalledHomeWifiIfNeeded() {
        try {
            String status = readSmallUtf8(new File("/data/hmgapi/state/services.status"));
            String asks = readSmallUtf8(new File("/data/hmgapi/state/wifi.txt"));
            String wifiSh = readSmallUtf8(new File("/data/hmgapi/state/wifi.sh"));
            if (!BouncePolicy.shouldProbeHomeWifiDaemonSocket(status, asks, wifiSh)) {
                return;
            }
            android.net.LocalSocket s = new android.net.LocalSocket();
            boolean connected = false;
            boolean ok = false;
            try {
                s.connect(new android.net.LocalSocketAddress(
                        "hmgapi-wifi", android.net.LocalSocketAddress.Namespace.ABSTRACT));
                connected = true;
                s.setSoTimeout(800);
                byte[] req = new byte[] {
                    'L', 'F', 'D', '1',
                    1, 0, 0, 0,
                    6, 0, 0, 0,
                    's', 't', 'a', 't', 'u', 's'
                };
                java.io.OutputStream out = s.getOutputStream();
                out.write(req);
                out.flush();
                byte[] hdr = new byte[8];
                java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
                in.readFully(hdr);
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
            if (connected && !ok) {
                File ctlDir = new File("/data/hmgapi/state/ctl");
                ctlDir.mkdirs();
                File ctl = new File(ctlDir, "bounce-wifi-" + android.os.Process.myPid());
                writeSmallUtf8(ctl, "restart hmgapi wifi", true);
                log("detected unresponsive @hmgapi-wifi; queued hmgapi/wifi restart");
            }
        } catch (Throwable ignored) {}
    }

    private static String readProcCmdline(File cmdFile) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(cmdFile);
            byte[] buf = new byte[128];
            int n = fis.read(buf);
            if (n <= 0) return "";
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            return new String(buf, 0, end, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isCarIgnitionOn() {
        int ign = invokeBooleanBinder("com.hkmc.micom.bcm", "getIGNStatus");
        if (ign == 1) return true;
        int pwr = invokeIntBinder("com.hkmc.micom.power", "getPowerStatus", -99);
        return ign < 0 && pwr == 0;
    }

    private static int readBluetoothState() {
        int st = invokeIntBinder("bluetooth_manager", "getState", -1);
        if (st >= 0) return st;
        int en = invokeBooleanBinder("bluetooth_manager", "isEnabled");
        if (en == 1) return BouncePolicy.BT_STATE_ON;
        if (en == 0) return BouncePolicy.BT_STATE_OFF;
        return -1;
    }

    private static boolean isCarPlayConnected() {
        if (invokeIntBinder("dipo", "getCurrentDiPOStat", -1) == 1) return true;
        String cpAddr = invokeStringBinder("phoneprojection", "getCPConnectedBTAddress");
        return !BouncePolicy.normalizeBtMac(cpAddr).isEmpty();
    }

    private static boolean isAndroidAutoConnected() {
        int gpmSt = invokeIntBinder("gpm", "getGpmStatus", -1);
        if (gpmSt >= 3) return true;
        String aaAddr = invokeStringBinder("phoneprojection", "getAAConnectedBTAddress");
        return !BouncePolicy.normalizeBtMac(aaAddr).isEmpty();
    }

    private static boolean isAnyCallActive() {
        if (invokeBooleanBinder("phoneprojection", "isBtCalling") == 1) return true;
        if (invokeBooleanBinder("phoneprojection", "isBtIncomingCall") == 1) return true;
        if (invokeBooleanBinder("gpm", "isGpmCall") == 1) return true;
        return invokeIntBinder("dipo", "getDiPOCallstatus", 0) > 0;
    }

    private static boolean isAnyCameraCurrentlyActive() {
        int modeRear = invokeBooleanBinder("com.hkmc.micom.mode", "isRearViewOn");
        String fgPkg = invokeStringBinder("com.hkmc.micom.mode", "getForegroundPackageName");
        boolean pgs = invokeBooleanBinder("com.hkmc.micom.pgs", "getReverseStatus") == 1
                || invokeBooleanBinder("com.hkmc.micom.pgs", "isDrivingRearViewOn") == 1
                || invokeBooleanBinder("com.hkmc.micom.pgs", "isFrontViewOn") == 1;
        return BouncePolicy.isCameraActive(pgs, modeRear, fgPkg);
    }

    private static boolean isHomeWifiHoldOnDevice(long nowElapsedMs) {
        String boot = readSmallUtf8(BOOT_ID_FILE).trim();
        String wifi = readSmallUtf8(WIFI_JSON_FILE);
        String ka = readSmallUtf8(KEEPAWAKE_FILE);
        String olKa = readSmallUtf8(OPENLINK_KEEPAWAKE_FILE);
        return BouncePolicy.isHomeWifiOrParkedHoldActive(boot, nowElapsedMs, wifi, ka, olKa);
    }

    private static boolean readResetProjectionAppsSetting() {
        try {
            String rawState = readSmallUtf8(STATE_JSON_FILE);
            if (!rawState.isEmpty()) {
                JSONObject st = new JSONObject(rawState);
                JSONObject mods = st.optJSONObject("mods");
                JSONObject m = mods != null ? mods.optJSONObject(MOD_ID) : null;
                JSONObject s = m != null ? m.optJSONObject("settings") : null;
                if (s != null && s.has("reset_projection_apps")) {
                    return s.optBoolean("reset_projection_apps", true);
                }
            }
            String rawLocal = readSmallUtf8(MOD_SETTINGS_FILE);
            if (!rawLocal.isEmpty()) {
                JSONObject s = new JSONObject(rawLocal);
                if (s.has("reset_projection_apps")) {
                    return s.optBoolean("reset_projection_apps", true);
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private static void publishProjectionResumeLease(String bootId, int preMode, String btAddr, long nowElapsedMs) {
        try {
            File dir = PROJECTION_RESUME_FILE.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
                dir.setReadable(true, false);
                dir.setExecutable(true, false);
            }
            String json = BouncePolicy.buildProjectionResumeJson(bootId, preMode, btAddr, nowElapsedMs);
            File tmp = new File(PROJECTION_RESUME_FILE.getAbsolutePath() + ".tmp");
            writeSmallUtf8(tmp, json, true);
            if (tmp.renameTo(PROJECTION_RESUME_FILE)) {
                PROJECTION_RESUME_FILE.setReadable(true, false);
            } else {
                writeSmallUtf8(PROJECTION_RESUME_FILE, json, true);
                tmp.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static void writeLastResultJson(
            long wallMs,
            String trigger,
            String outcome,
            String discDetail,
            int workers,
            long downMs,
            long apUpMs,
            long btUpMs,
            long radiosMs,
            long projUpMs,
            long totalMs,
            String nudge) {
        try {
            JSONObject o = new JSONObject();
            o.put("version", BouncePolicy.VERSION);
            o.put("timestampMs", wallMs);
            o.put("trigger", trigger);
            o.put("outcome", outcome);
            o.put("disconnect", discDetail);
            o.put("refreshedWorkers", workers);
            o.put("downMs", downMs);
            o.put("apUpMs", apUpMs);
            o.put("btUpMs", btUpMs);
            o.put("radiosReadyMs", radiosMs);
            o.put("projUpMs", projUpMs);
            o.put("totalMs", totalMs);
            o.put("nudge", nudge);
            File tmp = new File(BOUNCE_LAST_FILE.getAbsolutePath() + ".tmp");
            writeSmallUtf8(tmp, o.toString() + "\n", true);
            if (tmp.renameTo(BOUNCE_LAST_FILE)) {
                BOUNCE_LAST_FILE.setReadable(true, false);
            } else {
                tmp.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static long readConfirmStamp() {
        try {
            String s = readSmallUtf8(CONFIRM_MARKER_FILE).trim();
            return s.isEmpty() ? 0L : Long.parseLong(s);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static void writeConfirmStamp(long nowElapsedMs) {
        writeSmallUtf8(CONFIRM_MARKER_FILE, nowElapsedMs + "\n", true);
    }

    private static void sendToastAsync(final String message) {
        if (message == null || message.isEmpty()) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent("com.hkmc.intent.action.TOAST")
                        .putExtra("com.hkmc.intent.extra.TOAST_MESSAGE", message)
                        .putExtra("com.hkmc.intent.extra.TOAST_POPUPSHOWING", true);
                if (!broadcastViaActivityManager(intent)) {
                    runAmBroadcastFallback(
                            "-a", "com.hkmc.intent.action.TOAST",
                            "-e", "com.hkmc.intent.extra.TOAST_MESSAGE", message,
                            "--ez", "com.hkmc.intent.extra.TOAST_POPUPSHOWING", "true");
                }
            }
        }, "hmgapi-bounce-toast");
        t.setDaemon(true);
        t.start();
    }

    private static void sendActionBroadcast(String action) {
        if (action == null || action.isEmpty()) return;
        Intent intent = new Intent(action);
        if (!broadcastViaActivityManager(intent)) {
            runAmBroadcastFallback("-a", action);
        }
    }

    private static boolean broadcastViaActivityManager(Intent intent) {
        try {
            Class<?> amn = Class.forName("android.app.ActivityManagerNative");
            Object am = amn.getMethod("getDefault").invoke(null);
            if (am == null) return false;
            Method[] methods = am.getClass().getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (!"broadcastIntent".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 12) {
                    m.invoke(am, null, intent, null, null, Integer.valueOf(0), null, null,
                            null, Integer.valueOf(-1), Boolean.FALSE, Boolean.FALSE, Integer.valueOf(-1));
                    return true;
                } else if (p.length == 11) {
                    m.invoke(am, null, intent, null, null, Integer.valueOf(0), null, null,
                            null, Boolean.FALSE, Boolean.FALSE, Integer.valueOf(-1));
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void runAmBroadcastFallback(String... extraArgs) {
        try {
            List<String> cmd = new ArrayList<String>(extraArgs.length + 2);
            cmd.add("/system/bin/am");
            cmd.add("broadcast");
            for (int i = 0; i < extraArgs.length; i++) cmd.add(extraArgs[i]);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] buf = new byte[128];
            while (p.getInputStream().read(buf) >= 0) {
                // drain
            }
            p.getInputStream().close();
            p.waitFor();
        } catch (Throwable ignored) {}
    }

    private static void waitForBootCompleted(long maxWaitMs) {
        long start = SystemClock.elapsedRealtime();
        while ((SystemClock.elapsedRealtime() - start) < maxWaitMs) {
            if ("1".equals(getSysProp("sys.boot_completed"))) return;
            sleepQuietly(150L);
        }
    }

    private static String getSysProp(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return v instanceof String ? (String) v : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static synchronized Object getProxy(String serviceName) {
        try {
            IBinder prev = cachedBinders.get(serviceName);
            Object prevProxy = cachedProxies.get(serviceName);
            if (prev != null && prevProxy != null && prev.isBinderAlive()) {
                return prevProxy;
            }
            cachedBinders.remove(serviceName);
            cachedProxies.remove(serviceName);

            Object raw = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, serviceName);
            if (!(raw instanceof IBinder)) return null;
            IBinder binder = (IBinder) raw;
            String desc = null;
            try {
                desc = binder.getInterfaceDescriptor();
            } catch (Throwable ignored) {}
            if (desc == null || desc.isEmpty()) {
                desc = fallbackDescriptor(serviceName);
            }
            if (desc == null || desc.isEmpty()) return null;
            Class<?> stub = Class.forName(desc + "$Stub");
            Object proxy = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (proxy != null) {
                cachedBinders.put(serviceName, binder);
                cachedProxies.put(serviceName, proxy);
            }
            return proxy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String fallbackDescriptor(String serviceName) {
        if ("wifi".equals(serviceName)) return "android.net.wifi.IWifiManager";
        if ("bluetooth_manager".equals(serviceName)) return "android.bluetooth.IBluetoothManager";
        if ("phoneprojection".equals(serviceName)) return "android.phoneprojection.IPPManager";
        if ("gpm".equals(serviceName)) return "com.hkmc.gpm.IGpmInterfaces";
        if ("dipo".equals(serviceName)) return "android.dipo.IDiPOManager";
        return "";
    }

    private static int invokeIntBinder(String svc, String method, int fallback) {
        try {
            Object p = getProxy(svc);
            if (p == null) return fallback;
            Object r = p.getClass().getMethod(method).invoke(p);
            return r instanceof Integer ? ((Integer) r).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int invokeBooleanBinder(String svc, String method) {
        try {
            Object p = getProxy(svc);
            if (p == null) return -1;
            Object r = p.getClass().getMethod(method).invoke(p);
            if (r instanceof Boolean) return ((Boolean) r).booleanValue() ? 1 : 0;
            if (r instanceof Integer) return ((Integer) r).intValue();
            return -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String invokeStringBinder(String svc, String method) {
        try {
            Object p = getProxy(svc);
            if (p == null) return "";
            Object r = p.getClass().getMethod(method).invoke(p);
            return r instanceof String ? ((String) r).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void invokeNoArgBinder(String svc, String method) {
        try {
            Object p = getProxy(svc);
            if (p != null) p.getClass().getMethod(method).invoke(p);
        } catch (Throwable ignored) {}
    }

    private static String readSmallUtf8(File f) {
        if (f == null || !f.isFile()) return "";
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            byte[] buf = new byte[1024];
            int n;
            while ((n = fis.read(buf)) >= 0 && bos.size() < 32768) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void writeSmallUtf8(File f, String content, boolean worldReadable) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            if (worldReadable) {
                f.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0L) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String msg) {
        String full = "[hmgapi-bounce] " + msg;
        try {
            Log.i(HMG_TAG, full);
            Log.i(TAG, msg);
        } catch (Throwable ignored) {}
        System.out.println(full);
    }

    private static void logErr(String msg, Throwable t) {
        String full = "[hmgapi-bounce] ERROR " + msg + ": " + t;
        try {
            Log.e(HMG_TAG, full, t);
            Log.e(TAG, msg, t);
        } catch (Throwable ignored) {}
        System.err.println(full);
    }
}
