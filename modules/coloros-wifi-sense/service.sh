#!/system/bin/sh

MODDIR=${0%/*}
STATE_DIR=/data/adb/coloros_wifi_sense

(
    while [ "$(getprop sys.boot_completed)" != 1 ]; do
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
        sleep 2
    done
    [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
    export CLASSPATH="$MODDIR/wifi-sense.dex"
    exec /system/bin/app_process /system/bin --nice-name=coloros-wifi-sense \
        ColorOsWifiSense "$MODDIR" "$STATE_DIR"
) </dev/null >/dev/null 2>&1 &
