# Bounce Connection (`hmgapi-bounce`)

**Bounce Connection** (`v0.1.3`) is a lightweight, standalone **[HMG-API](https://github.com/Open-HMG/HMG-API)** (`>= 1.1.0-rc.x`) mod (`AGPL-3.0`) for **Hyundai, Kia, and Genesis Gen5W** head units that performs a fast (`~5 s`), cold-boot-safe soft-reset of **5 GHz Connectivity Wi-Fi SoftAP**, **Bluetooth**, and **Wireless CarPlay / Android Auto** without rebooting the head unit.

It works out of the box on **stock `Open-HMG/HMG-API`** (`>= 1.1.0-rc.x`) with **zero custom Zygote hook points and zero dependencies on any other mod**.

---

## Requirements

- **Hyundai / Kia / Genesis Standard-Class Gen5W** head unit (`tcc803x` / `tcc897x`)
- **[HMG-API](https://github.com/Open-HMG/HMG-API)** **`1.1.0` (`>= 1.1.0-rc.x`) or newer**
  *(Uses standard `1.1.0` `provides.launcher`, `provides.star_key`, `car_events`, and on-demand `services` — works with stock `Open-HMG/hmgapi-wireless-cp-aa` or plain Bluetooth.)*

---

## Key Features

1. **Zero Idle RAM & Zero Idle CPU**
   - Uses HMG-API's on-demand root service model (`"start": "manual", "restart": false`). Nothing runs in the background while idle.
2. **1-Tap Home Screen Tile & Eyes-Free `★` Key Action**
   - **Home Screen Tile (`Bounce Connection`)**: Launches a zero-UI translucent trampoline (`BounceActivity`) that triggers the bounce immediately and keeps you on the Home Screen with native head-unit toast notifications (`"Bouncing Wi-Fi & Bluetooth…"` -> `"Ready — reconnecting phone"`).
   - **Custom `★` Button (`provides.star_key`)**: Assign **`Bounce Connection`** to either the steering-wheel `★` or head-unit `★` in `Setup -> Button`.
   - **NEMESIS Launch**: Tapping **Launch** in NEMESIS Manager triggers a bounce directly.
3. **6-Phase Gen5W-Safe Soft-Reset (`~5 s`)**
   - **Phase 1 — Clean Protocol Disconnect**: Sends `PPService.disconnectPP`, `onWirelessDisconnected`, and `disconnectWirelessAll` (`350 ms` flush) so the phone closes its wireless projection socket cleanly.
   - **Phase 2 — Volatile Radio Down & Smart Audio Policy**: Holds `muteAV` unless FM/AM/SiriusXM/USB media is actively playing, calls `IBluetoothManager.disable(false)` (`persist = false` — **never** setting `Settings.Global.BLUETOOTH_ON = 0`), and disables client Wi-Fi + SoftAP (`setWifiApEnabledForConnectivity(null, false)`).
   - **Phase 3 — Safe Worker Refresh (while Bluetooth is OFF)**: When `reset_projection_apps` is enabled (`true` default), sends protocol-scoped `SIGTERM` to `com.google.android.projection.sink` (`HKMC_GPM`) and/or `com.daudio.app.dipo` (`HKMC_DiPO`) while keeping `com.daudio.app.phoneprojection` alive and bound to `PPService` (`IWLDeviceInfo`), and drains `WIFI_AP_STATE_DISABLING (10)` before re-enabling SoftAP.
   - **Phase 4 — `5 GHz` Connectivity SoftAP Up First**: Brings Mode 2 SoftAP (`apSt == 13`) online **before** Bluetooth so `hostapd` is already broadcasting `ConnectivitySSID` when the phone shakes hands over Bluetooth.
   - **Phase 5 — Bluetooth Up (Duplicate-Enable Safe)**: Enables Bluetooth (`btState == 12`) while skipping duplicate `enable()` calls if already `STATE_TURNING_ON (11)` (avoiding Android 4.4's `60 s` `MESSAGE_TIMEOUT_BIND` drop).
   - **Phase 6 — Household Multi-Phone Reconnect & Focus Handoff**: Prefers `PPService.getBtHfpConnectDevice()` on the `+6 s` `connectPP` fallback nudge (supporting multi-driver households), switches focus to CarPlay or Android Auto once connected (deferring while any reverse/AVM camera is active), and writes timing metrics (with SHA-256-redacted BT identifiers) to `HMG-API` logs and `/data/hmgapi/public/bounce-last.json`.
4. **Active Phone Call & Home Wi-Fi Guardrails (with 5-Second Double-Tap Force)**
   - Silent auto-bounces always yield to active phone calls and parked Home Wi-Fi sessions. Manual taps warn on the first tap and allow a second tap within `5 s` to force the bounce anyway.
5. **Optional Silent Standby-Resume Modes (`HMG-API -> Bounce Connection -> Settings`)**
   - **Off** *(default)* — Manual Home Screen tile or `★` button only.
   - **Smart Watchdog** — Silently bounces once if Bluetooth connects to a phone in the cabin without CarPlay/AA starting within `15 s` of startup/wake.
   - **Proactive** — Silently bounces immediately on every standby / quick-stop wake.

---

## Installation

### Option A — HMG-API Updates via USB Stick *(Recommended)*
1. Extract **`hmgapi-bounce-v0.1.3-hmgapi-usb.zip`** onto the root of a **FAT32** USB drive (producing `HMGAPI/updates/hmgapi-bounce-v0.1.3.zip`).
2. Plug the USB drive into the car's data USB port, open **HMG-API -> Updates**, install **Bounce Connection**, tap **Apply changes**, and **restart once** so the Home Screen tile and `★` button action register.

### Option B — NEMESIS USB Package Store
1. Extract **`hmgapi-bounce-v0.1.3-nemesis-usb.zip`** onto the root of a **FAT32** USB drive (`NEMESIS.sh` + `packages/hmgapi.hmgapi-bounce.zip`).
2. Plug the USB drive into the car, install **Bounce Connection** from the NEMESIS prompt, and **restart once**.

### Option C — Over ADB (USB or Home Wi-Fi)
```bash
adb connect <HEAD_UNIT_IP>:5555
adb push hmgapi-bounce-v0.1.3.zip /data/local/tmp/pkg.zip
adb shell "/system/bin/hmgapid engine install /data/local/tmp/pkg.zip && /system/bin/hmgapid engine apply && rm -f /data/local/tmp/pkg.zip"
adb shell "/system/bin/hmgapid reboot"
```
*(After the initial install's one-time restart, future updates apply live without rebooting.)*

---

## Building from Source

```bash
./build.sh
```

`build.sh` automatically fetches and SHA-256-verifies the pinned upstream `Open-HMG/HMG-API` Mod SDK (`hmgapi-sdk.json`), compiles `Bounce.apk`, runs host Java unit tests (`BounceUnitTest`), engine tests (`test.sh`), and POSIX head-unit shell checks, and outputs signed installable archives into `dist/`.
