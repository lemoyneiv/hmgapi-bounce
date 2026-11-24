package dev.lemoyne.bounce;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Pure-Java policy and state decisions for {@code hmgapi-bounce}.
 *
 * <p>Kept free of {@code android.*} imports so every guardrail, audio-focus rule,
 * worker-process filter, multi-phone target choice, and watchdog condition runs
 * directly under host {@code javac}/{@code java} in {@code BounceUnitTest}.</p>
 */
public final class BouncePolicy {

    private BouncePolicy() {}

    public static final String VERSION = "0.1.3";

    /** Gen5W ModeManager ModeType constants. */
    public static final int MODE_NONE = 0;
    public static final int MODE_BT_AUDIO = 54;
    public static final int MODE_CARPLAY = 115;
    public static final int MODE_ANDROID_AUTO = 116;

    /** Gen5W MicomAudioManager audio mode constants. */
    public static final int MICOM_BT_AUDIO = 14;
    public static final int AUDIO_CARPLAY = 22;
    public static final int AUDIO_ANDROID_AUTO = 23;

    /** Android WifiManager SoftAP state constants. */
    public static final int WIFI_AP_STATE_DISABLING = 10;
    public static final int WIFI_AP_STATE_DISABLED = 11;
    public static final int WIFI_AP_STATE_ENABLING = 12;
    public static final int WIFI_AP_STATE_ENABLED = 13;

    /** Android BluetoothAdapter state constants. */
    public static final int BT_STATE_OFF = 10;
    public static final int BT_STATE_TURNING_ON = 11;
    public static final int BT_STATE_ON = 12;
    public static final int BT_STATE_TURNING_OFF = 13;

    /** PPService projection type constants. */
    public static final int PP_TYPE_CARPLAY = 1;
    public static final int PP_TYPE_ANDROID_AUTO = 2;

    /** Timing budgets & lease windows. */
    public static final long DOUBLE_TAP_CONFIRM_WINDOW_MS = 5_000L;
    public static final long LEASE_FRESHNESS_MS = 30_000L;
    public static final long PROJECTION_RESUME_LEASE_MS = 45_000L;
    public static final long DISCONNECT_GRACE_MS = 350L;
    public static final long RADIO_DOWN_TIMEOUT_MS = 3_500L;
    public static final long WORKER_SETTLE_MS = 300L;
    public static final long SOFTAP_DISABLING_DRAIN_MS = 6_000L;
    public static final long SOFTAP_UP_TIMEOUT_MS = 4_000L;
    public static final long BT_UP_TIMEOUT_MS = 4_500L;
    public static final long RECONNECT_NUDGE_DELAY_MS = 6_000L;
    public static final long RECONNECT_WATCH_MAX_MS = 18_000L;
    public static final long WATCHDOG_DELAY_MS = 15_000L;

    public static final String SHORTCUT_CARPLAY = "com.hkmc.intent.action.DIPO_CUSTOM_SHORTCUT";
    public static final String SHORTCUT_ANDROID_AUTO = "com.hkmc.intent.action.GPM_CUSTOM_SHORTCUT";
    public static final String FOCUS_DEFER_CAMERA = "DEFER_CAMERA";

    public static final String PKG_GPM_SINK = "com.google.android.projection.sink";
    public static final String PKG_DIPO = "com.daudio.app.dipo";
    public static final String PKG_PHONE_PROJECTION = "com.daudio.app.phoneprojection";

    private static final String[] CAMERA_PACKAGES = {
        "com.hkmc.system.app.rearcamera",
        "com.hkmc.app.rearcamera",
        "com.daudio.app.rearcamera",
        "com.hkmc.app.avm",
        "com.daudio.app.avm",
        "com.hkmc.avm.ui",
        "com.lge.app.avm"
    };

    public enum GuardDecision {
        PROCEED,
        PROCEED_FORCED,
        REFUSE_CALL_CONFIRM,
        REFUSE_HOME_WIFI_CONFIRM,
        SKIP_SILENT_CALL,
        SKIP_SILENT_HOME_WIFI
    }

    /**
     * Evaluates pre-bounce safety guardrails for active phone calls and parked Home Wi-Fi.
     * Silent auto-bounces always yield; manual triggers refuse on the first tap and allow a
     * second tap within {@link #DOUBLE_TAP_CONFIRM_WINDOW_MS} (5s) to force the bounce.
     */
    public static GuardDecision evaluateGuard(
            boolean manual,
            boolean callActive,
            boolean ignOn,
            boolean homeWifiHold,
            long confirmStampElapsedMs,
            long nowElapsedMs) {
        boolean secondTapValid = manual
                && confirmStampElapsedMs > 0L
                && nowElapsedMs >= confirmStampElapsedMs
                && (nowElapsedMs - confirmStampElapsedMs) <= DOUBLE_TAP_CONFIRM_WINDOW_MS;

        if (callActive) {
            if (!manual) return GuardDecision.SKIP_SILENT_CALL;
            return secondTapValid ? GuardDecision.PROCEED_FORCED : GuardDecision.REFUSE_CALL_CONFIRM;
        }
        if (!ignOn && homeWifiHold) {
            if (!manual) return GuardDecision.SKIP_SILENT_HOME_WIFI;
            return secondTapValid ? GuardDecision.PROCEED_FORCED : GuardDecision.REFUSE_HOME_WIFI_CONFIRM;
        }
        return GuardDecision.PROCEED;
    }

    /**
     * Holds hardware {@code muteAV()} during the bounce/reconnect window unless:
     *  - the driver already muted audio manually ({@code userMute == 1}), or
     *  - non-Bluetooth, non-Projection media (FM/AM/HD Radio, SiriusXM, USB media, Nature Sounds)
     *    is actively playing.
     */
    public static boolean shouldHoldHardwareMute(int curMode, int audMode, int userMute) {
        if (userMute == 1) {
            return false;
        }
        boolean nonBtNonProjMode = curMode > 0
                && curMode != MODE_BT_AUDIO
                && curMode != MODE_CARPLAY
                && curMode != MODE_ANDROID_AUTO;
        boolean nonBtNonProjAudio = audMode > 0
                && audMode != MICOM_BT_AUDIO
                && audMode != AUDIO_CARPLAY
                && audMode != AUDIO_ANDROID_AUTO;
        if (nonBtNonProjMode || nonBtNonProjAudio) {
            return false;
        }
        return true;
    }

    /**
     * Strictly allowlists only {@code com.google.android.projection.sink} and
     * {@code com.daudio.app.dipo} for Phase 3 {@code SIGTERM} worker refresh.
     * Explicitly refuses {@code com.daudio.app.phoneprojection} (keeping {@code PPService}'s
     * {@code IWLDeviceInfo} bound) and {@code system_server}.
     */
    public static boolean isAllowedWorkerTarget(String processCmdline) {
        if (processCmdline == null || processCmdline.isEmpty()) return false;
        String clean = processCmdline.trim();
        if (PKG_PHONE_PROJECTION.equals(clean) || "system_server".equals(clean)) {
            return false;
        }
        return PKG_GPM_SINK.equals(clean) || PKG_DIPO.equals(clean);
    }

    /**
     * Scopes Phase 3 worker {@code SIGTERM} refresh to the active projection protocol when known:
     * skips {@code com.daudio.app.dipo} during pure Android Auto bounces (unless forced) so stock
     * KitKat {@code ActivityManagerService} does not emit a benign {@code CarPlayService} map {@code wtf}.
     */
    public static boolean shouldRefreshWorker(
            String processCmdline,
            boolean hadAa,
            boolean hadCp,
            boolean forced) {
        if (!isAllowedWorkerTarget(processCmdline)) return false;
        String clean = processCmdline.trim();
        if (forced) return true;
        if (hadAa && !hadCp) {
            return PKG_GPM_SINK.equals(clean);
        }
        if (hadCp && !hadAa) {
            return PKG_DIPO.equals(clean);
        }
        return true;
    }

    public static boolean isCameraActive(boolean pgsCameraActive, int modeRearView, String foregroundPkg) {
        if (pgsCameraActive || modeRearView == 1) return true;
        if (foregroundPkg == null || foregroundPkg.isEmpty()) return false;
        String pkg = foregroundPkg.trim();
        for (int i = 0; i < CAMERA_PACKAGES.length; i++) {
            if (CAMERA_PACKAGES[i].equals(pkg)) return true;
        }
        return false;
    }

    public static String decideFocusShortcut(
            boolean cpUp,
            boolean aaUp,
            int curMode,
            boolean cameraActive) {
        if (!cpUp && !aaUp) return "";
        if (cpUp && curMode == MODE_CARPLAY) return "";
        if (aaUp && !cpUp && curMode == MODE_ANDROID_AUTO) return "";
        if (cameraActive) return FOCUS_DEFER_CAMERA;
        return cpUp ? SHORTCUT_CARPLAY : SHORTCUT_ANDROID_AUTO;
    }

    public static String normalizeBtMac(String addr) {
        if (addr == null) return "";
        String s = addr.trim().toUpperCase(Locale.US);
        if (s.length() != 17 || "00:00:00:00:00:00".equals(s)) return "";
        for (int i = 0; i < 17; i++) {
            char c = s.charAt(i);
            if (i % 3 == 2) {
                if (c != ':' && c != '-') return "";
            } else {
                boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
                if (!hex) return "";
            }
        }
        return s.replace('-', ':');
    }

    /**
     * Multi-phone household target picker for the +6s {@code PPService.connectPP} fallback nudge:
     * always prefers whichever phone just connected Bluetooth HFP/ACL in the cabin right now,
     * falling back to pre-bounce session MACs only if HFP has not reported yet.
     */
    public static String selectReconnectNudgeTarget(
            String liveHfpMac,
            String liveConnMac,
            String preAaMac,
            String preCpMac,
            String preHfpMac,
            String preConnMac) {
        String[] ordered = {
            liveHfpMac, liveConnMac, preAaMac, preCpMac, preHfpMac, preConnMac
        };
        for (int i = 0; i < ordered.length; i++) {
            String m = normalizeBtMac(ordered[i]);
            if (!m.isEmpty()) return m;
        }
        return "";
    }

    /**
     * Determines {@code PPService.connectPP} projection type ({@code 1} CarPlay, {@code 2} Android Auto)
     * for {@code targetMac}.
     */
    public static int selectProjectionTypeForNudge(
            String targetMac,
            boolean cpAutoAvailable,
            int wlTransferType,
            String preAaMac,
            String preCpMac,
            int preMode) {
        String norm = normalizeBtMac(targetMac);
        if (norm.isEmpty()) return 0;
        if (cpAutoAvailable || wlTransferType == PP_TYPE_CARPLAY) {
            return PP_TYPE_CARPLAY;
        }
        if (wlTransferType == PP_TYPE_ANDROID_AUTO) {
            return PP_TYPE_ANDROID_AUTO;
        }
        if (!norm.isEmpty() && norm.equals(normalizeBtMac(preCpMac))) {
            return PP_TYPE_CARPLAY;
        }
        if (!norm.isEmpty() && norm.equals(normalizeBtMac(preAaMac))) {
            return PP_TYPE_ANDROID_AUTO;
        }
        if (preMode == MODE_CARPLAY) return PP_TYPE_CARPLAY;
        return PP_TYPE_ANDROID_AUTO;
    }

    /**
     * Smart Watchdog trigger rule: fires a single silent bounce only when ignition is ON,
     * Bluetooth is ON and connected to a phone in the cabin, neither CarPlay nor Android Auto
     * has started after 15s, no call is active, and Home Wi-Fi is not holding {@code wlan0}.
     */
    public static boolean shouldWatchdogTriggerBounce(
            boolean ignOn,
            int btState,
            String liveHfpMac,
            String liveConnMac,
            boolean cpUp,
            boolean aaUp,
            boolean callActive,
            boolean homeWifiHold) {
        if (!ignOn || btState != BT_STATE_ON) return false;
        if (cpUp || aaUp || callActive || homeWifiHold) return false;
        return !normalizeBtMac(liveHfpMac).isEmpty() || !normalizeBtMac(liveConnMac).isEmpty();
    }

    /**
     * Prevents calling {@code IBluetoothManager.enable()} a second time when Android 4.4
     * {@code BluetoothManagerService} is already in {@code STATE_TURNING_ON (11)} or
     * {@code STATE_ON (12)} (which otherwise arms {@code MESSAGE_TIMEOUT_BIND} and drops BT 60s later).
     */
    public static boolean shouldInvokeBluetoothEnable(int btState) {
        return btState != BT_STATE_TURNING_ON && btState != BT_STATE_ON;
    }

    /**
     * Returns true once {@code WifiStateMachine} has finished exiting {@code SoftApStoppingState}
     * ({@code WIFI_AP_STATE_DISABLING == 10}) and is neither active nor enabling.
     */
    public static boolean isSoftApReadyForReenable(int apState) {
        return apState != WIFI_AP_STATE_DISABLING
                && apState != WIFI_AP_STATE_ENABLING
                && apState != WIFI_AP_STATE_ENABLED;
    }

    public static boolean isHomeWifiOrParkedHoldActive(
            String curBootId,
            long nowElapsedMs,
            String wifiJson,
            String keepawakeJson,
            String openlinkKeepawakeJson) {
        String wifiSt = extractJsonString(wifiJson, "state");
        if ("up".equalsIgnoreCase(wifiSt) || "searching".equalsIgnoreCase(wifiSt)) {
            return true;
        }
        return isActiveLeaseFresh(curBootId, nowElapsedMs, keepawakeJson)
                || isActiveLeaseFresh(curBootId, nowElapsedMs, openlinkKeepawakeJson);
    }

    /**
     * Gates {@code @hmgapi-wifi} health probes so stock {@code Open-HMG/HMG-API} installs
     * (whose {@code state/wifi.sh} is a pure shell loop with no {@code HomeWifiDaemon} socket)
     * are never falsely restarted, while custom {@code HomeWifiDaemon} builds still self-heal.
     */
    public static boolean shouldProbeHomeWifiDaemonSocket(
            String servicesStatus,
            String wifiAsks,
            String wifiShContent) {
        if (servicesStatus == null || !servicesStatus.contains("hmgapi wifi running ")) {
            return false;
        }
        if (wifiAsks == null || !wifiAsks.contains("home")) {
            return false;
        }
        return wifiShContent != null && wifiShContent.contains("HomeWifiDaemon");
    }

    /**
     * Identifies transient {@code hmgapid} worker-slot saturation ({@code EX_TEMPFAIL == 75} or
     * stock {@code MAX_WORKERS} {@code EPIPE} {@code "Broken pipe"}) so client IPC calls retry
     * cleanly on both stock and custom {@code HMG-API} builds.
     */
    public static boolean isTransientHmgapidBusyError(String message) {
        if (message == null || message.isEmpty()) return false;
        String low = message.toLowerCase(Locale.US);
        return low.contains("hmgapid is busy") || low.contains("broken pipe");
    }

    static boolean isActiveLeaseFresh(String curBootId, long nowElapsedMs, String json) {
        if (json == null || json.isEmpty()) return false;
        String norm = json.replace(" ", "");
        if (!norm.contains("\"active\":true")) return false;
        String boot = extractJsonString(json, "bootId");
        String cur = curBootId == null ? "" : curBootId.trim();
        if (!cur.isEmpty() && !boot.isEmpty() && !cur.equals(boot)) {
            return false;
        }
        long ts = extractJsonLong(json, "updatedElapsedMs", -1L);
        if (ts < 0L) ts = extractJsonLong(json, "elapsedMs", -1L);
        if (ts > 0L && nowElapsedMs > 0L && Math.abs(nowElapsedMs - ts) > LEASE_FRESHNESS_MS) {
            return false;
        }
        return true;
    }

    public static String buildProjectionResumeJson(
            String bootId,
            int preMode,
            String btAddr,
            long nowElapsedMs) {
        int effMode = (preMode == MODE_CARPLAY || preMode == MODE_ANDROID_AUTO)
                ? preMode
                : MODE_ANDROID_AUTO;
        String src = (effMode == MODE_CARPLAY) ? "cp" : "aa";
        String cleanBoot = escapeJson(bootId == null ? "" : bootId.trim());
        String cleanMac = normalizeBtMac(btAddr);
        long exp = Math.max(0L, nowElapsedMs) + PROJECTION_RESUME_LEASE_MS;
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"bootId\":\"").append(cleanBoot).append('"');
        sb.append(",\"preMode\":").append(effMode);
        sb.append(",\"source\":\"").append(src).append('"');
        if (!cleanMac.isEmpty()) {
            sb.append(",\"btAddr\":\"").append(cleanMac).append('"');
        }
        sb.append(",\"resumedAtElapsedMs\":").append(Math.max(0L, nowElapsedMs));
        sb.append(",\"expiresAtElapsedMs\":").append(exp);
        sb.append("}\n");
        return sb.toString();
    }

    public static String redactMac(String mac) {
        String norm = normalizeBtMac(mac);
        if (norm.isEmpty()) return "none";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(("hmgapi-bounce:" + norm).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(9);
            hex.append("bt#");
            for (int i = 0; i < 3; i++) {
                int v = dig[i] & 0xff;
                if (v < 16) hex.append('0');
                hex.append(Integer.toHexString(v));
            }
            return hex.toString();
        } catch (Exception e) {
            return "bt#redacted";
        }
    }

    static String extractJsonString(String json, String key) {
        if (json == null || key == null) return "";
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 <= q1) return "";
        return json.substring(q1 + 1, q2);
    }

    static long extractJsonLong(String json, String key, long fallback) {
        if (json == null || key == null) return fallback;
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return fallback;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return fallback;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        if (i < json.length() && json.charAt(i) == '-') i++;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (i == start || (i == start + 1 && json.charAt(start) == '-')) return fallback;
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String escapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c >= 0x20) out.append(c);
        }
        return out.toString();
    }
}
