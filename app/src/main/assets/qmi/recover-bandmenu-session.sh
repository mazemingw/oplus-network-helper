#!/system/bin/sh
# Release only the verified NAS daemon belonging to dev.qcom.bandmenu.
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

bandmenu_recover "$@"
