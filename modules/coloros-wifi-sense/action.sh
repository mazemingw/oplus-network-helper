#!/system/bin/sh

MODDIR=${0%/*}
current=$(sh "$MODDIR/settings.sh") || exit 1
case "$current" in
    -75) next=-80 ;;
    -80) next=-85 ;;
    -85) next=-90 ;;
    -90) next=-75 ;;
    *) next=-80 ;;
esac
sh "$MODDIR/settings.sh" "$next"
