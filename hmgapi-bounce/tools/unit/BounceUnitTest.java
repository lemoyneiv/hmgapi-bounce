package dev.lemoyne.bounce;

/**
 * Host unit test suite for {@link BouncePolicy}.
 */
public final class BounceUnitTest {

    private static int assertions = 0;

    private BounceUnitTest() {}

    public static void main(String[] args) {
        testGuardrailsAndDoubleTapForce();
        testHardwareMutePreservesRadioAndSxm();
        testWorkerTargetAllowlistSafety();
        testMultiPhoneNudgeAndProjectionType();
        testFocusShortcutAndCameraDeferral();
        testSmartWatchdogRule();
        testHomeWifiHoldAndResumeJson();
        System.out.println("BounceUnitTest: " + assertions + " assertions PASSED.");
    }

    private static void testGuardrailsAndDoubleTapForce() {
        // Normal manual & silent proceed
        checkEq("Manual proceeds when idle",
                BouncePolicy.GuardDecision.PROCEED,
                BouncePolicy.evaluateGuard(true, false, true, false, 0L, 10_000L));
        checkEq("Silent proceeds when IG ON and no call",
                BouncePolicy.GuardDecision.PROCEED,
                BouncePolicy.evaluateGuard(false, false, true, false, 0L, 10_000L));

        // Active phone call: silent always skips; manual refuses 1st tap, forces on 2nd tap <= 5s
        checkEq("Silent skips on active call",
                BouncePolicy.GuardDecision.SKIP_SILENT_CALL,
                BouncePolicy.evaluateGuard(false, true, true, false, 9_000L, 10_000L));
        checkEq("Manual refuses 1st tap during call",
                BouncePolicy.GuardDecision.REFUSE_CALL_CONFIRM,
                BouncePolicy.evaluateGuard(true, true, true, false, 0L, 10_000L));
        checkEq("Manual forces on 2nd tap within 5000ms during call",
                BouncePolicy.GuardDecision.PROCEED_FORCED,
                BouncePolicy.evaluateGuard(true, true, true, false, 10_000L, 14_999L));
        checkEq("Manual refuses again once 5000ms confirm window expires",
                BouncePolicy.GuardDecision.REFUSE_CALL_CONFIRM,
                BouncePolicy.evaluateGuard(true, true, true, false, 10_000L, 15_001L));

        // Parked Home Wi-Fi hold: allowed when IG ON, guarded when IG OFF
        checkEq("Manual proceeds immediately when IG ON even if wifi.json not yet cleared",
                BouncePolicy.GuardDecision.PROCEED,
                BouncePolicy.evaluateGuard(true, false, true, true, 0L, 20_000L));
        checkEq("Silent skips when IG OFF and Home Wi-Fi active",
                BouncePolicy.GuardDecision.SKIP_SILENT_HOME_WIFI,
                BouncePolicy.evaluateGuard(false, false, false, true, 0L, 20_000L));
        checkEq("Manual refuses 1st tap when IG OFF and Home Wi-Fi active",
                BouncePolicy.GuardDecision.REFUSE_HOME_WIFI_CONFIRM,
                BouncePolicy.evaluateGuard(true, false, false, true, 0L, 20_000L));
        checkEq("Manual forces on 2nd tap within 5s when IG OFF and Home Wi-Fi active",
                BouncePolicy.GuardDecision.PROCEED_FORCED,
                BouncePolicy.evaluateGuard(true, false, false, true, 20_000L, 23_500L));
    }

    private static void testHardwareMutePreservesRadioAndSxm() {
        // Idle, BT Audio, CarPlay, Android Auto -> hold mute
        check("Hold mute when idle (mode=0, aud=0)",
                BouncePolicy.shouldHoldHardwareMute(0, 0, 0));
        check("Hold mute when Bluetooth Audio active (mode=54, aud=14)",
                BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_BT_AUDIO, BouncePolicy.MICOM_BT_AUDIO, 0));
        check("Hold mute when Android Auto active (mode=116, aud=23)",
                BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_ANDROID_AUTO, BouncePolicy.AUDIO_ANDROID_AUTO, 0));
        check("Hold mute when CarPlay active (mode=115, aud=22)",
                BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_CARPLAY, BouncePolicy.AUDIO_CARPLAY, 0));

        // FM/AM/SiriusXM/USB media -> do NOT mute
        check("Do NOT hold mute when FM/SXM radio playing (mode=11, aud=1)",
                !BouncePolicy.shouldHoldHardwareMute(11, 1, 0));
        check("Do NOT hold mute when SiriusXM playing (mode=41, aud=5)",
                !BouncePolicy.shouldHoldHardwareMute(41, 5, 0));

        // User already muted -> never touch muteAV/unMuteAV
        check("Do NOT touch muteAV when userMute==1",
                !BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_ANDROID_AUTO, BouncePolicy.AUDIO_ANDROID_AUTO, 1));
    }

    private static void testWorkerTargetAllowlistSafety() {
        check("Allows HKMC_GPM projection.sink",
                BouncePolicy.isAllowedWorkerTarget("com.google.android.projection.sink"));
        check("Allows HKMC_DiPO",
                BouncePolicy.isAllowedWorkerTarget("com.daudio.app.dipo"));
        check("NEVER kills com.daudio.app.phoneprojection (PPService IWLDeviceInfo deadlock safety)",
                !BouncePolicy.isAllowedWorkerTarget("com.daudio.app.phoneprojection"));
        check("NEVER kills system_server",
                !BouncePolicy.isAllowedWorkerTarget("system_server"));
        check("NEVER kills com.hkmc.bluetooth",
                !BouncePolicy.isAllowedWorkerTarget("com.hkmc.bluetooth"));
        check("Rejects null/empty",
                !BouncePolicy.isAllowedWorkerTarget("") && !BouncePolicy.isAllowedWorkerTarget(null));

        // Protocol-scoped refresh avoids benign CarPlayService wtf during pure Android Auto bounces
        check("Android Auto bounce refreshes projection.sink",
                BouncePolicy.shouldRefreshWorker("com.google.android.projection.sink", true, false, false));
        check("Android Auto bounce skips dipo unless forced",
                !BouncePolicy.shouldRefreshWorker("com.daudio.app.dipo", true, false, false));
        check("Forced Android Auto bounce refreshes dipo too",
                BouncePolicy.shouldRefreshWorker("com.daudio.app.dipo", true, false, true));
        check("CarPlay bounce refreshes dipo",
                BouncePolicy.shouldRefreshWorker("com.daudio.app.dipo", false, true, false));
        check("CarPlay bounce skips projection.sink unless forced",
                !BouncePolicy.shouldRefreshWorker("com.google.android.projection.sink", false, true, false));
        check("Idle/unknown bounce refreshes both allowlisted workers",
                BouncePolicy.shouldRefreshWorker("com.google.android.projection.sink", false, false, false)
                        && BouncePolicy.shouldRefreshWorker("com.daudio.app.dipo", false, false, false));
    }

    private static void testMultiPhoneNudgeAndProjectionType() {
        String driverA = "aa:bb:cc:11:22:33";
        String driverB = "dd:ee:ff:44:55:66";

        // Driver B is in the car now (HFP connected to B), even though Driver A had AA pre-bounce
        checkEq("Prefers live HFP phone over pre-bounce session MAC",
                "DD:EE:FF:44:55:66",
                BouncePolicy.selectReconnectNudgeTarget(driverB, "", driverA, "", driverA, ""));
        // Falls back to pre-bounce MAC when HFP hasn't reported yet
        checkEq("Falls back to pre-bounce AA MAC when live HFP empty",
                "AA:BB:CC:11:22:33",
                BouncePolicy.selectReconnectNudgeTarget("", "00:00:00:00:00:00", driverA, "", "", ""));

        // Projection type resolution
        checkEq("Uses CarPlay (1) when CP auto-connect available",
                Integer.valueOf(BouncePolicy.PP_TYPE_CARPLAY),
                Integer.valueOf(BouncePolicy.selectProjectionTypeForNudge(driverB, true, 0, driverA, "", 116)));
        checkEq("Uses CarPlay (1) when target matches pre-bounce CarPlay MAC",
                Integer.valueOf(BouncePolicy.PP_TYPE_CARPLAY),
                Integer.valueOf(BouncePolicy.selectProjectionTypeForNudge(driverA, false, 0, "", driverA, 0)));
        checkEq("Uses Android Auto (2) when target matches pre-bounce AA MAC",
                Integer.valueOf(BouncePolicy.PP_TYPE_ANDROID_AUTO),
                Integer.valueOf(BouncePolicy.selectProjectionTypeForNudge(driverA, false, 0, driverA, "", 0)));
    }

    private static void testFocusShortcutAndCameraDeferral() {
        check("Detects Gen5W system rearcamera package",
                BouncePolicy.isCameraActive(false, 0, "com.hkmc.system.app.rearcamera"));
        check("Detects modeRearView==1",
                BouncePolicy.isCameraActive(false, 1, "com.android.launcher3"));
        check("No camera when launcher foreground and PGS idle",
                !BouncePolicy.isCameraActive(false, 0, "com.android.launcher3"));

        checkEq("Defers shortcut while camera active",
                BouncePolicy.FOCUS_DEFER_CAMERA,
                BouncePolicy.decideFocusShortcut(false, true, 11, true));
        checkEq("Dispatches GPM shortcut when AA connects over fallback radio",
                BouncePolicy.SHORTCUT_ANDROID_AUTO,
                BouncePolicy.decideFocusShortcut(false, true, 11, false));
        checkEq("Dispatches DIPO shortcut when CarPlay connects",
                BouncePolicy.SHORTCUT_CARPLAY,
                BouncePolicy.decideFocusShortcut(true, false, 54, false));
        checkEq("No shortcut needed when AA already foreground (mode 116)",
                "",
                BouncePolicy.decideFocusShortcut(false, true, BouncePolicy.MODE_ANDROID_AUTO, false));
    }

    private static void testSmartWatchdogRule() {
        check("Watchdog fires when IG ON, BT ON, HFP phone connected, and no AA/CP",
                BouncePolicy.shouldWatchdogTriggerBounce(
                        true, BouncePolicy.BT_STATE_ON, "AA:BB:CC:11:22:33", "", false, false, false, false));
        check("Watchdog stays quiet when no phone is in the car",
                !BouncePolicy.shouldWatchdogTriggerBounce(
                        true, BouncePolicy.BT_STATE_ON, "", "", false, false, false, false));
        check("Watchdog stays quiet when AA already connected",
                !BouncePolicy.shouldWatchdogTriggerBounce(
                        true, BouncePolicy.BT_STATE_ON, "AA:BB:CC:11:22:33", "", false, true, false, false));
        check("Watchdog stays quiet during active call",
                !BouncePolicy.shouldWatchdogTriggerBounce(
                        true, BouncePolicy.BT_STATE_ON, "AA:BB:CC:11:22:33", "", false, false, true, false));
    }

    private static void testHomeWifiHoldAndResumeJson() {
        check("Detects Home Wi-Fi state=up",
                BouncePolicy.isHomeWifiOrParkedHoldActive("boot-1", 50_000L, "{\"state\":\"up\"}", "", ""));
        check("Detects fresh OpenLink keepawake lease on same boot",
                BouncePolicy.isHomeWifiOrParkedHoldActive(
                        "boot-1", 50_000L, "{\"state\":\"waiting\"}", "",
                        "{\"active\":true,\"bootId\":\"boot-1\",\"updatedElapsedMs\":40000}"));
        check("Ignores stale keepawake lease from previous boot",
                !BouncePolicy.isHomeWifiOrParkedHoldActive(
                        "boot-2", 50_000L, "{\"state\":\"waiting\"}", "",
                        "{\"active\":true,\"bootId\":\"boot-1\",\"updatedElapsedMs\":40000}"));

        String resume = BouncePolicy.buildProjectionResumeJson("boot-xyz", 116, "aa:bb:cc:dd:ee:ff", 12_000L);
        check("projection-resume.json carries bootId, preMode=116, normalized btAddr, and 45s lease",
                resume.contains("\"bootId\":\"boot-xyz\"")
                        && resume.contains("\"preMode\":116")
                        && resume.contains("\"source\":\"aa\"")
                        && resume.contains("\"btAddr\":\"AA:BB:CC:DD:EE:FF\"")
                        && resume.contains("\"expiresAtElapsedMs\":57000"));

        String redacted = BouncePolicy.redactMac("AA:BB:CC:DD:EE:FF");
        check("redactMac produces deterministic bt#xxxxxx tag without raw octets",
                redacted.startsWith("bt#") && redacted.length() == 9 && !redacted.contains("AA"));

        // v0.1.1: Duplicate IBluetoothManager.enable() guard & SoftAP AP_STATE_DISABLING drain check
        check("shouldInvokeBluetoothEnable skips duplicate enable() when TURNING_ON (11) or ON (12)",
                !BouncePolicy.shouldInvokeBluetoothEnable(BouncePolicy.BT_STATE_TURNING_ON)
                        && !BouncePolicy.shouldInvokeBluetoothEnable(BouncePolicy.BT_STATE_ON)
                        && BouncePolicy.shouldInvokeBluetoothEnable(BouncePolicy.BT_STATE_OFF));
        check("isSoftApReadyForReenable waits while AP_STATE_DISABLING (10) and allows DISABLED (11)",
                !BouncePolicy.isSoftApReadyForReenable(BouncePolicy.WIFI_AP_STATE_DISABLING)
                        && !BouncePolicy.isSoftApReadyForReenable(BouncePolicy.WIFI_AP_STATE_ENABLED)
                        && BouncePolicy.isSoftApReadyForReenable(BouncePolicy.WIFI_AP_STATE_DISABLED));
    }

    private static void check(String label, boolean cond) {
        assertions++;
        if (!cond) {
            throw new AssertionError("Assertion failed: " + label);
        }
    }

    private static void checkEq(String label, Object expected, Object actual) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
