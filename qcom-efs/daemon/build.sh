#!/usr/bin/env bash
# Builds the aarch64 daemon and drops it into the app's assets.
#
# Deliberately NOT -static: a statically linked NDK binary gets a TLS segment
# aligned to 8 bytes, and bionic on Android 11 and older refuses to start it
# ("executable's TLS segment is underaligned").  Linking against the platform
# libc avoids that, and is smaller anyway.
#
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973 ./build.sh
#
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../src/main/assets/nh-efsd}"
api="${API:-26}"

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$ndk" ]; then
    echo "Set ANDROID_NDK_HOME to your NDK directory." >&2
    exit 1
fi

case "$(uname -s)" in
    Linux)       host=linux-x86_64 ;;
    Darwin)      host=darwin-x86_64 ;;
    MINGW*|MSYS*|CYGWIN*) host=windows-x86_64 ;;
    *)           echo "unsupported build host" >&2; exit 1 ;;
esac

cc="$ndk/toolchains/llvm/prebuilt/$host/bin/clang"
[ -x "$cc" ] || cc="$cc.exe"
[ -x "$cc" ] || { echo "clang not found under $ndk" >&2; exit 1; }

mkdir -p "$(dirname "$out")"

"$cc" \
    --target="aarch64-linux-android$api" \
    -Os -flto \
    -fno-strict-aliasing -fstack-protector-strong \
    -Wall -Wextra -Wno-unused-parameter \
    -o "$out" \
    "$here/src/util.c" "$here/src/diag.c" "$here/src/efs2.c" "$here/src/main.c"

if command -v llvm-strip >/dev/null 2>&1; then
    llvm-strip "$out" || true
elif [ -x "$ndk/toolchains/llvm/prebuilt/$host/bin/llvm-strip" ]; then
    "$ndk/toolchains/llvm/prebuilt/$host/bin/llvm-strip" "$out" || true
fi

echo "built $out ($(wc -c < "$out") bytes)"
