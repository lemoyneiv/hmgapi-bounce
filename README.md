# Bounce Connection (`hmgapi-bounce`)

**Bounce Connection** (`v0.1.2`) is a lightweight, standalone **HMG-API** (`>= 1.1.0`) mod (`AGPL-3.0`) for **Hyundai, Kia, and Genesis Gen5W** head units that performs a fast (`~5 s`), cold-boot-safe soft-reset of **5 GHz Connectivity Wi-Fi SoftAP**, **Bluetooth**, and **Wireless CarPlay / Android Auto** without rebooting the head unit.

---

## Key Features

1. **Zero Idle RAM & Zero Idle CPU**
   - Uses HMG-API's on-demand root service model (`"start": "manual", "restart": false`). Nothing runs in the background while idle.
2. **1-Tap Home Screen Tile & Eyes-Free `★` Key Action**
   - **Home Screen Tile (`Bounce Connection`)**: Launches a zero-UI translucent trampoline (`BounceActivity`) that triggers the bounce immediately and keeps you on the Home Screen with native Hyundai toast notifications (`"Bouncing Wi-Fi & Bluetooth…"` -> `"Ready — reconnecting phone"`).
   - **Custom `★` Button (`provides.star_key`)**: Assign **`Bounce Connection`** to either the steering-wheel `★` or head-unit `★` in `Setup -> Button`.
3. **6-Phase Gen5W-Safe Soft-Reset (`~5 s`)**
   - **Phase 1 — Clean Protocol Disconnect**: Sends `PPService.disconnectPP`, `onWirelessDisconnected`, and `disconnectWirelessAll` (`350 ms` flush) so the phone closes its wireless projection socket cleanly.
   - **Phase 2 — Volatile Radio Down & Smart Audio Policy**: Holds `muteAV` unless FM/AM/SiriusXM/USB media is actively playing, calls `IBluetoothManager.disable(false)` (`persist = false` + `/dev/.hmgapi-bounce-bt` `tmpfs` — **never** setting `Settings.Global.BLUETOOTH_ON = 0`), and disables client Wi-Fi + SoftAP (`setWifiApEnabledForConnectivity(null, false)`).
   - **Phase 3 — Safe Worker Refresh (while Bluetooth is OFF)**: When `reset_projection_apps` is enabled (`true` default), sends `SIGTERM` to `com.google.android.projection.sink` (`HKMC_GPM`) and `com.daudio.app.dipo` (`HKMC_DiPO`) while keeping `com.daudio.app.phoneprojection` alive and bound to `PPService`.
   - **Phase 4 — `5 GHz` Connectivity SoftAP Up First**: Brings Mode 2 SoftAP (`apSt == 13`) online **before** Bluetooth so `hostapd` is already broadcasting `ConnectivitySSID` when the phone shakes hands over Bluetooth.
   - **Phase 5 — Focus-Guard Lease + Bluetooth Up**: Writes `/data/hmgapi/public/projection-resume.json` (`45 s` lease to prevent Bluetooth Audio Mode `54` from stealing focus) and enables Bluetooth (`btState == 12`).
   - **Phase 6 — Household Multi-Phone Reconnect & Focus Handoff**: Prefers `PPService.getBtHfpConnectDevice()` on the `+6 s` `connectPP` fallback nudge (supporting multi-driver households), switches focus to CarPlay or Android Auto once connected (deferring while any reverse/AVM camera is active), and writes timing metrics to `HMG-API` logs and `/data/hmgapi/public/bounce-last.json`.
4. **Active Phone Call & Home Wi-Fi Guardrails (with 5-Second Double-Tap Force)**
   - Silent auto-bounces always yield to active phone calls and parked Home Wi-Fi sessions. Manual taps warn on the first tap and allow a second tap within `5 s` to force the bounce anyway.
5. **Optional Silent Standby-Resume Modes (`HMG-API -> Bounce Connection -> Settings`)**
   - **Off** *(default)* — Manual Home Screen tile or `★` button only.
   - **Smart Watchdog** — Silently bounces once if Bluetooth connects to a phone in the cabin without CarPlay/AA starting within `15 s` of startup/wake.
   - **Proactive** — Silently bounces immediately on every standby / quick-stop wake.

---

## Install Over Home Wi-Fi ADB

```bash
adb connect <CAR_LAN_IP>:5555
adb push hmgapi-bounce-v0.1.2.zip /data/local/tmp/pkg.zip
adb shell "/system/bin/hmgapid engine install /data/local/tmp/pkg.zip && /system/bin/hmgapid engine apply && rm -f /data/local/tmp/pkg.zip"
```

*(Note: After first installing a mod that adds a new Home Screen tile or `★` button choice, restart the head unit once with `adb shell "/system/bin/hmgapid reboot"` so the Zygote loads the new tile and `★` action; subsequent updates apply live.)*
