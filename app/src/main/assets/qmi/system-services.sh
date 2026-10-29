#!/system/bin/sh
# Explicit init-service recovery. No system process is killed and no init configuration is changed.
# argv: private directory, own qmi directory, host APK, UID, host PID, host start ticks, operation, [service|0|1]
store=$1; helpers=$2; apk=$3; uid=$4; host=$5; birth=$6; operation=$7; argument=$8
case "$uid:$host:$birth" in *[!0-9:]*|:*|*::*) exit 2;; esac
[ "$(id -u)" = 0 ] || { echo 'Root required' >&2; exit 1; }
mkdir -p "$store/state" || exit 1
chmod 700 "$store/state"
journal=$store/state
boot=$(cat /proc/sys/kernel/random/boot_id) || exit 1
services='qti-modem-daemon-1 qti-modem-daemon-0 vendor.qms vendor.qcrild2 vendor.qcrild'
fail() { echo "$*" >&2; exit 1; }
identity() {
    case "$1" in
        qti-modem-daemon-0|qti-modem-daemon-1)
            binary=/odm/bin/hw/subsys_daemon; rc=/odm/etc/init/subsys_daemon.rc
            suffix=${1##*-}; expected="-l /odm/lib64/libqti-radio-service.so -i $suffix";;
        vendor.qms) binary=/vendor/bin/qms; rc=/vendor/etc/init/qms.rc; expected='';;
        vendor.qcrild) binary=/vendor/bin/hw/qcrilNrd; rc=/vendor/etc/init/qcrilNrd.rc; expected='';;
        vendor.qcrild2) binary=/vendor/bin/hw/qcrilNrd; rc=/vendor/etc/init/qcrilNrd.rc; expected='-c 2';;
        *) return 1;;
    esac
    definition=$(awk -v name="$1" '$1 == "service" && $2 == name { $1=""; $2=""; sub(/^ +/, ""); print; exit }' "$rc" 2>/dev/null)
    [ "$definition" = "$binary${expected:+ $expected}" ] || return 1
    current=$(getprop "init.svc.$1")
    if [ "$current" = running ]; then
        service_pid=$(getprop "init.svc_debug_pid.$1")
        case "$service_pid" in ''|*[!0-9]*) return 1;; esac
        [ "$(readlink /proc/$service_pid/exe)" = "$binary" ] || return 1
        actual=$(tr '\000' ' ' < /proc/$service_pid/cmdline 2>/dev/null)
        [ "${actual% }" = "$definition" ] || return 1
    fi
}
owned() { [ "$(cat "$journal/$1" 2>/dev/null)" = "$boot" ]; }
lock() {
    n=0
    until mkdir "$journal/lock" 2>/dev/null; do
        owner=$(awk '{print $1}' "$journal/lock/pid" 2>/dev/null)
        owner_birth=$(awk '{print $2}' "$journal/lock/pid" 2>/dev/null)
        case "$owner" in ''|*[!0-9]*) ;; *)
            live_birth=$(sed 's/.*) //' /proc/$owner/stat 2>/dev/null | awk '{print $20}')
            if [ "$owner_birth" != "$live_birth" ]; then rm -f "$journal/lock/pid"; rmdir "$journal/lock" 2>/dev/null; fi;;
        esac
        if [ -z "$owner" ]; then
            modified=$(stat -c %Y "$journal/lock" 2>/dev/null)
            now=$(date +%s)
            if [ -n "$modified" ] && [ "$((now-modified))" -gt 2 ]; then rmdir "$journal/lock" 2>/dev/null; fi
        fi
        n=$((n+1)); [ "$n" -lt 100 ] || fail 'System component control busy'
        sleep 0.1
    done
    echo "$$ $(sed 's/.*) //' /proc/$$/stat | awk '{print $20}')" > "$journal/lock/pid"
    trap 'rm -f "$journal/lock/pid"; rmdir "$journal/lock" 2>/dev/null' EXIT
}
wait_state() {
    n=0
    while [ "$(getprop "init.svc.$1")" != "$2" ]; do
        n=$((n+1)); [ "$n" -lt 60 ] || return 1
        sleep 0.1
    done
}
release_helpers() {
    # Only this package's verified helpers. PID alone is never an identity.
    for file in "$helpers"/pid-*; do
        [ -f "$file" ] || continue
        pid=$(cat "$file")
        case "$pid" in ''|*[!0-9]*) continue;; esac
        exe=$(readlink /proc/$pid/exe)
        case "$exe" in "$helpers"/nh-qmi-*) ;; *) continue;; esac
        cmd=$(tr '\000' ' ' < /proc/$pid/cmdline 2>/dev/null)
        case "$cmd" in *"nh_qmi_${uid}_${file##*pid-}"*) kill "$pid" 2>/dev/null;; esac
    done
    for proc in /proc/[0-9]*; do
        [ -r "$proc/cmdline" ] || continue
        cmd=$(tr '\000' ' ' < "$proc/cmdline" 2>/dev/null)
        case "$cmd" in bunnylockd*)
            grep -F -- "$apk" "$proc/maps" >/dev/null 2>&1 || continue
            case "$(readlink "$proc/exe")" in /system/bin/app_process*) kill "${proc##*/}" 2>/dev/null;; esac;;
        esac
    done
    sleep 0.3
}
restore_owned() {
    pending=0
    for svc in $services; do owned "$svc" && pending=1; done
    [ "$pending" = 1 ] || return 0
    release_helpers
    failures=0
    for svc in $services; do
        owned "$svc" || continue
        if identity "$svc" && setprop ctl.start "$svc" && wait_state "$svc" running; then
            rm -f "$journal/$svc"
            echo "Restored $svc" >> "$journal/events"
        else
            failures=$((failures+1)); echo "Restore failed: $svc" >&2
        fi
    done
    [ "$failures" = 0 ]
}
start_watch() {
    [ "$(cat "$journal/auto" 2>/dev/null)" = 1 ] || return 0
    # A guardian belongs to a single host process incarnation. Reuse only that incarnation.
    watch=$(cat "$journal/watch" 2>/dev/null)
    case "$watch" in ''|*[!0-9]*) ;; *)
        cmd=$(tr '\000' ' ' < /proc/$watch/cmdline 2>/dev/null)
        case "$cmd" in *"$store"*"$host $birth watch"*) kill -0 "$watch" 2>/dev/null && return 0;; esac;;
    esac
    setsid /system/bin/sh "$0" "$store" "$helpers" "$apk" "$uid" "$host" "$birth" watch </dev/null >>"$journal/events" 2>&1 &
    watcher=$!
    echo "$watcher" > "$journal/watch"
    sleep 0.1
    kill -0 "$watcher" 2>/dev/null || fail 'Auto-restore guardian could not start; no component was paused'
}
stop_watch() {
    watcher=$(cat "$journal/watch" 2>/dev/null)
    case "$watcher" in ''|*[!0-9]*) return 0;; esac
    cmd=$(tr '\000' ' ' < /proc/$watcher/cmdline 2>/dev/null)
    case "$cmd" in *"$store/system-services.sh"*' watch '*)
        case "$(readlink /proc/$watcher/exe)" in /system/bin/sh|/system/bin/mksh)
            kill "$watcher" 2>/dev/null;; esac;;
    esac
    rm -f "$journal/watch"
}
case "$operation" in
    inspect)
        for svc in $services; do
            valid=0; identity "$svc" && valid=1
            ours=0; owned "$svc" && ours=1
            printf '%s\t%s\t%s\t%s\t%s\n' "$svc" "$(getprop init.svc.$svc)" "$(getprop init.svc_debug_pid.$svc)" "$valid" "$ours"
        done
        tail -n 12 "$journal/events" 2>/dev/null | sed 's/^/LOG\t/'
        exit 0;;
    watch)
        # Process death/force-stop fallback, independent of Activity.onDestroy delivery.
        while [ "$(cat "$journal/auto" 2>/dev/null)" = 1 ]; do
            live=$(sed 's/.*) //' /proc/$host/stat 2>/dev/null | awk '{print $20}')
            if [ "$live" != "$birth" ]; then
                lock
                [ "$(cat "$journal/auto" 2>/dev/null)" = 1 ] && restore_owned
                exit $?
            fi
            sleep 1
        done
        exit 0;;
esac
lock
case "$operation" in
    configure)
        case "$argument" in 0|1) ;; *) fail 'Invalid auto-restore setting';; esac
        # Serialized with restoration: a quick off/on cannot reuse an already exiting guardian.
        stop_watch
        echo "$argument" > "$journal/auto.$$" && mv "$journal/auto.$$" "$journal/auto" || fail 'Cannot save auto-restore setting'
        [ "$argument" != 1 ] || start_watch;;
    pause)
        identity "$argument" || fail 'Service definition/process does not match the supported component'
        [ "$(getprop init.svc.$argument)" = running ] || fail 'Service is not running; original state preserved'
        start_watch
        # Journal before the mutation, including crash between ctl.stop and completion.
        echo "$boot" > "$journal/pending.$$" && mv "$journal/pending.$$" "$journal/$argument" || fail 'Cannot save original component state'
        if ! setprop ctl.stop "$argument" || ! wait_state "$argument" stopped; then
            fail "Pause failed: $argument (restoration journal retained)"
        fi
        echo "Paused $argument" >> "$journal/events";;
    resume)
        identity "$argument" || fail 'Service definition/process does not match the supported component'
        release_helpers
        setprop ctl.start "$argument" && wait_state "$argument" running || fail "Resume failed: $argument"
        rm -f "$journal/$argument"
        echo "Manually resumed $argument" >> "$journal/events";;
    restore) restore_owned || exit 1;;
    exit)
        if [ "$(cat "$journal/auto" 2>/dev/null)" = 1 ]; then restore_owned || exit 1; fi;;
    *) fail 'Unsupported system component operation';;
esac
