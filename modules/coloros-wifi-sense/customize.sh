#!/system/bin/sh

[ "$API" -ge 29 ] || abort "colorOSWIFI感知需要 Android 10 或更新版本"

module_version=$(sed -n 's/^version=//p' "$MODPATH/module.prop")
ui_print "欢迎使用ColorOS WIFI感知模块！"
ui_print "====="
ui_print "本模块的功能是处理弱信号不会自动断开WIFI的问题"
ui_print "WIFI未连接时不会检测，降低耗电"
ui_print "作者：酷安@NVMEX夜莺"
ui_print "版本：$module_version"
ui_print "====="
ui_print "模块功能选择：按音量上下键选择"
ui_print "音量+：开启核心及流体云通知（推荐）"
ui_print "音量-：仅开启核心"

command -v getevent >/dev/null 2>&1 || abort "无法读取音量按键，请在开机后的 Magisk/KernelSU 管理器中安装"
while :; do
    key_event=$(getevent -qlc 1 2>/dev/null) || abort "读取音量按键失败"
    case "$key_event" in
        *KEY_VOLUMEUP*DOWN*|*KEY_VOLUMEUP*00000001*|*0001*0073*00000001*)
            printf '1\n' > "$MODPATH/fluid_enabled" || abort "无法保存功能选择"
            ui_print "已选择：核心及流体云通知"
            break ;;
        *KEY_VOLUMEDOWN*DOWN*|*KEY_VOLUMEDOWN*00000001*|*0001*0072*00000001*)
            printf '0\n' > "$MODPATH/fluid_enabled" || abort "无法保存功能选择"
            rm -f "$MODPATH/wifi-fluid.dex"
            ui_print "已选择：仅开启核心"
            break ;;
    esac
done

STATE_DIR=/data/adb/coloros_wifi_sense
(umask 077; mkdir -p "$STATE_DIR") || abort "无法创建阈值配置目录"
if [ ! -f "$STATE_DIR/threshold" ]; then
    (umask 077; printf '%s\n' -80 > "$STATE_DIR/threshold") || abort "无法保存默认阈值"
fi
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/settings.sh" 0 0 0755
set_perm "$MODPATH/demo.sh" 0 0 0755
set_perm "$MODPATH/fluid_enabled" 0 0 0600
ui_print "安装完成，重启后开始监听 Wi-Fi。"
