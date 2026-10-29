#!/system/bin/sh
# Discover and release verified competing backend services. Never clear modem locks.
# No modem requests, preference setters, radio-service restarts, or global pkill.

bandmenu_read_cmdline() {
    # One bounded read: some vendor kernels make repeated reads of an opened
    # cmdline spin after its process exits. Feed tr a pipe, never that proc fd.
    dd if="/proc/$1/cmdline" bs=4096 count=1 2>/dev/null | tr '\000' ' '
}

bandmenu_matches() {
    task_command=$1
    task_owner=$2
    task_user=$3
    task_base="/data/user/$task_user/dev.qcom.bandmenu/files"
    [ "$task_command" = "$task_base/qcom-bandlockd -uid $task_owner -tokenfile $task_base/auth_token " ] && return 0
    if [ "$task_user" = 0 ]; then
        task_base='/data/data/dev.qcom.bandmenu/files'
        [ "$task_command" = "$task_base/qcom-bandlockd -uid $task_owner -tokenfile $task_base/auth_token " ] && return 0
    fi
    return 1
}

bandmenu_recover() {
    task_user=${1:-0}
    case "$task_user" in ''|*[!0-9]*) echo 'Invalid Android user' >&2; return 10;; esac
    [ "$(id -u)" = 0 ] || { echo 'Root access required' >&2; return 11; }
    task_packages=$(cmd package list packages -U --user "$task_user" dev.qcom.bandmenu) || return 12
    task_owner=$(printf '%s\n' "$task_packages" | while read -r task_package task_uid task_extra; do
        if [ "$task_package" = package:dev.qcom.bandmenu ] && [ -z "$task_extra" ]; then
            case "$task_uid" in uid:*) printf '%s' "${task_uid#uid:}";; esac
        fi
    done)
    case "$task_owner" in
        '') printf 'bandmenu_stopped=0\nbandmenu_released=0\n'; return 0;;
        *[!0-9]*) echo 'Invalid package UID' >&2; return 13;;
    esac
    [ "$((task_owner / 100000))" = "$task_user" ] || return 14
    task_processes=$(ps -A -o PID,NAME) || return 15
    task_pids=$(printf '%s\n' "$task_processes" | while read -r task_pid task_name task_extra; do
        [ "$task_name" = qcom-bandlockd ] && [ -z "$task_extra" ] || continue
        case "$task_pid" in ''|*[!0-9]*) continue;; esac
        [ "$task_pid" -gt 1 ] && [ -r "/proc/$task_pid/cmdline" ] || continue
        task_command=$(bandmenu_read_cmdline "$task_pid") || continue
        bandmenu_matches "$task_command" "$task_owner" "$task_user" && printf '%s\n' "$task_pid"
    done)
    if [ -z "$task_pids" ]; then
        printf 'bandmenu_stopped=0\nbandmenu_released=0\n'
        return 0
    fi
    # Prevent the app from immediately recreating the conflicting daemon.
    am force-stop --user "$task_user" dev.qcom.bandmenu || return 16
    task_released=0
    for task_pid in $task_pids; do
        [ -r "/proc/$task_pid/cmdline" ] || continue
        task_command=$(bandmenu_read_cmdline "$task_pid") || continue
        # Recheck identity after force-stop, immediately before signalling this PID.
        if bandmenu_matches "$task_command" "$task_owner" "$task_user"; then
            kill "$task_pid" || { [ ! -d "/proc/$task_pid" ] || return 17; }
            task_released=$((task_released + 1))
        fi
    done
    task_attempt=0
    while [ "$task_attempt" -lt 10 ]; do
        task_remaining=0
        for task_pid in $task_pids; do
            [ -r "/proc/$task_pid/cmdline" ] || continue
            task_command=$(bandmenu_read_cmdline "$task_pid") || continue
            bandmenu_matches "$task_command" "$task_owner" "$task_user" && task_remaining=1
        done
        if [ "$task_remaining" = 0 ]; then
            printf 'bandmenu_stopped=1\nbandmenu_released=%s\n' "$task_released"
            return 0
        fi
        sleep 0.1
        task_attempt=$((task_attempt + 1))
    done
    echo 'Verified Band Menu daemon did not exit' >&2
    return 18
}

bunny_matches() {
    task_bunny_pid=$1
    task_bunny_apk=$2
    # A process name alone is not ownership evidence: our embedded helper uses
    # the same class/name. Verify the installed APK in its environment or maps.
    task_bunny_command=$(bandmenu_read_cmdline "$task_bunny_pid") || return 1
    case "$task_bunny_command" in
        'bunnylockd '*|'dev.ririxi.bunnylock:bunnylockd '*) ;;
        *) return 1;;
    esac
    if dd if="/proc/$task_bunny_pid/environ" bs=65536 count=1 2>/dev/null |
        tr '\000' '\n' | grep -F -x "CLASSPATH=$task_bunny_apk" >/dev/null; then return 0; fi
    awk -v apk="$task_bunny_apk" '$NF == apk {found=1} END {exit !found}' "/proc/$task_bunny_pid/maps" 2>/dev/null
}

bunny_recover() {
    task_user=${1:-0}
    task_bunny_apk=$(cmd package path --user "$task_user" dev.ririxi.bunnylock 2>/dev/null |
        sed -n 's/^package://p' | head -n 1)
    case "$task_bunny_apk" in /data/app/*/base.apk) ;; *) echo 'bunny_stopped=0'; return 0;; esac
    task_bunny_pids=''
    task_bunny_candidates=$(ps -A -o PID,NAME | while read -r task_bunny_pid task_bunny_name task_bunny_extra; do
        [ -z "$task_bunny_extra" ] || continue
        case "$task_bunny_name" in bunnylockd|dev.ririxi.bunnylock:bunnylockd) ;;
            *) continue;; esac
        case "$task_bunny_pid" in ''|*[!0-9]*) continue;; esac
        printf '%s\n' "$task_bunny_pid"
    done)
    for task_bunny_pid in $task_bunny_candidates; do
        [ "$task_bunny_pid" -gt 1 ] || continue
        bunny_matches "$task_bunny_pid" "$task_bunny_apk" && task_bunny_pids="$task_bunny_pids $task_bunny_pid"
    done
    [ -n "$task_bunny_pids" ] || { echo 'bunny_stopped=0'; return 0; }
    am force-stop --user "$task_user" dev.ririxi.bunnylock || return 20
    for task_bunny_pid in $task_bunny_pids; do
        bunny_matches "$task_bunny_pid" "$task_bunny_apk" || continue
        kill "$task_bunny_pid" || { [ ! -d "/proc/$task_bunny_pid" ] || return 21; }
    done
    task_bunny_attempt=0
    while [ "$task_bunny_attempt" -lt 10 ]; do
        task_bunny_remaining=0
        for task_bunny_pid in $task_bunny_pids; do
            bunny_matches "$task_bunny_pid" "$task_bunny_apk" && task_bunny_remaining=1
        done
        [ "$task_bunny_remaining" = 0 ] && { echo 'bunny_stopped=1'; return 0; }
        sleep 0.1
        task_bunny_attempt=$((task_bunny_attempt + 1))
    done
    echo 'Verified bunnylock service did not exit' >&2
    return 22
}

bandmenu_recover "$@" || exit $?
bunny_recover "$@"
