#!/bin/sh
# Off-car engine & package structure test for hmgapi-bounce using the HMG-API Mod SDK.
set -e
M=$(cd "$(dirname "$0")" && pwd)
B=${HMGAPI_MOD_BUILD:?set HMGAPI_MOD_BUILD to the built mod (the out dir of bin/build-mod.sh)}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
. "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked mod SDK}/test/lib.sh"

mkdir -p "$R/system" "$R/data/hmgapi/settings"
printf 'ro.board.platform=tcc803x\n' > "$R/props.txt"

check "built: Bounce.apk exists" yes "$(test -f "$B/app/Bounce.apk" && echo yes || echo no)"
DEX_STRINGS=$(unzip -p "$B/app/Bounce.apk" classes.dex | strings)
has "apk contains BouncePolicy" "Ldev/lemoyne/bounce/BouncePolicy;" "$DEX_STRINGS"
has "apk contains BounceRunner" "Ldev/lemoyne/bounce/BounceRunner;" "$DEX_STRINGS"
has "apk contains BounceActivity" "Ldev/lemoyne/bounce/BounceActivity;" "$DEX_STRINGS"
has "apk contains BounceReceiver" "Ldev/lemoyne/bounce/BounceReceiver;" "$DEX_STRINGS"
has "apk contains BounceDispatcher" "Ldev/lemoyne/bounce/BounceDispatcher;" "$DEX_STRINGS"
has "apk bundles HmgApi client" "Lhmgapi/client/HmgApi;" "$DEX_STRINGS"

for ic in icon.png icon_night.png icon_disabled.png icon_disabled_night.png; do
    check "launcher tile $ic exists" yes "$(test -f "$M/icons/$ic" && echo yes || echo no)"
done

check "hmgapi-bounce attributed to lemoyneiv" "lemoyneiv" \
    "$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["authors"]))' "$M/mod.json")"
check "SOS/eCall button untouched in mod.json" no \
    "$(grep -qi '"sos"' "$M/mod.json" && echo yes || echo no)"

install_mod "$B"
cp -r "$M/icons" "$R/data/hmgapi/mods/hmgapi-bounce/"
cp -r "$M/scripts" "$R/data/hmgapi/mods/hmgapi-bounce/"
cp "$M/open.sh" "$R/data/hmgapi/mods/hmgapi-bounce/"

eng plan
check "engine plan succeeds" 0 "$rc"
has "plan lists Bounce.apk" "dev.lemoyne.bounce (app/Bounce.apk)" "$out"
has "plan lists manual bounce service" "service  hmgapi-bounce/bounce as root, at manual" "$out"
has "plan lists manual bounce-silent service" "service  hmgapi-bounce/bounce-silent as root, at manual" "$out"
has "plan lists manual watchdog service" "service  hmgapi-bounce/watchdog as root, at manual" "$out"
has "plan lists boot cleanup service" "service  hmgapi-bounce/cleanup as root, at boot" "$out"
check "plan has zero problems" no "$(echo "$out" | grep -q '^problem ' && echo yes || echo no)"

eng apply
check "engine apply succeeds" 0 "$rc"
has "mod published as on" "1 of 1 mods on" "$out"
has "mod in public state.json" '"hmgapi-bounce"' "$(cat "$R/data/hmgapi/public/state.json")"
has "manual bounce service registered in services.txt" \
    "hmgapi-bounce bounce root manual no jar app/Bounce.apk dev.lemoyne.bounce.BounceRunner manual" \
    "$(cat "$R/data/hmgapi/state/services.txt")"
has "manual bounce-silent service registered in services.txt" \
    "hmgapi-bounce bounce-silent root manual no jar app/Bounce.apk dev.lemoyne.bounce.BounceRunner silent" \
    "$(cat "$R/data/hmgapi/state/services.txt")"
has "manual watchdog service registered in services.txt" \
    "hmgapi-bounce watchdog root manual no jar app/Bounce.apk dev.lemoyne.bounce.BounceRunner watchdog" \
    "$(cat "$R/data/hmgapi/state/services.txt")"
has "boot cleanup fast-path script registered in services.txt" \
    "hmgapi-bounce cleanup root boot no script scripts/cleanup.sh" \
    "$(cat "$R/data/hmgapi/state/services.txt")"
has "vehicle service runs for wake/unparked car_events" \
    "hmgapi vehicle " \
    "$(cat "$R/data/hmgapi/state/services.txt")"

printf '{"reset_projection_apps":false,"auto_bounce_mode":"watchdog"}\n' > "$R/data/hmgapi/settings/hmgapi-bounce.json"
eng apply
check "settings override applies cleanly" 0 "$rc"
has "public state reflects watchdog mode" '"watchdog"' "$(cat "$R/data/hmgapi/public/state.json")"

eng remove hmgapi-bounce
check "engine remove succeeds" 0 "$rc"
eng apply
check "apply after remove succeeds" 0 "$rc"
exit "$fail"
