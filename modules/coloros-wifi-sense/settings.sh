#!/system/bin/sh

STATE_DIR=/data/adb/coloros_wifi_sense
THRESHOLD_FILE=$STATE_DIR/threshold

if [ "$#" -eq 0 ]; then
    if [ -f "$THRESHOLD_FILE" ]; then
        cat "$THRESHOLD_FILE"
    else
        printf '%s\n' -80
    fi
    exit
fi

threshold=$1
case "$#:$threshold" in
    1:-[2-9][0-9]|1:-100) ;;
    *) printf '%s\n' '阈值必须是 -100 至 -20 之间的整数（dBm）。' >&2; exit 1 ;;
esac

umask 077
mkdir -p "$STATE_DIR" || exit 1
temp_file=$(mktemp "$STATE_DIR/threshold.XXXXXX") || exit 1
trap 'rm -f "$temp_file"' EXIT
printf '%s\n' "$threshold" > "$temp_file" && mv -f "$temp_file" "$THRESHOLD_FILE" || exit 1
printf '阈值已设为 %s dBm，下次采样生效。\n' "$threshold"
