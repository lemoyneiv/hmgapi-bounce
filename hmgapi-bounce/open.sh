#!/system/bin/sh
# Trigger Bounce Connection from NEMESIS Manager -> Launch.
/system/bin/am broadcast -a dev.lemoyne.bounce.ACTION_BOUNCE -n dev.lemoyne.bounce/.BounceReceiver >/dev/null 2>&1
exit 0
