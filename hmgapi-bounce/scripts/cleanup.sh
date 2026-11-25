#!/system/bin/sh
[ -f /dev/.hmgapi-bounce-mute ] || [ -f /dev/.hmgapi-bounce-bt ] || exit 0
CLASSPATH="${HMGAPI_MOD_DIR:-/data/hmgapi/mods/hmgapi-bounce}/app/Bounce.apk" \
    exec /system/bin/app_process /system/bin dev.lemoyne.bounce.BounceRunner cleanup
