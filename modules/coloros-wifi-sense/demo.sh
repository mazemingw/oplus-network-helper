#!/system/bin/sh
MODDIR=${0%/*}
export CLASSPATH="$MODDIR/wifi-sense.dex"
exec /system/bin/app_process /system/bin --nice-name=coloros-wifi-demo ColorOsWifiSense --demo "$MODDIR"
