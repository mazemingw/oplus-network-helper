param([string]$NdkPath = "$env:LOCALAPPDATA/Android/Sdk/ndk/26.3.11579264")
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$llvmPath = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$testSourcePath = Join-Path $repoPath 'app/src/main/cpp/qmi/tests/nas-session-test.c'
$testBinaryPath = Join-Path $repoPath 'build/qmi-nas-session-test.wasm'
New-Item -ItemType Directory -Force -Path (Join-Path $repoPath 'build') | Out-Null
& (Join-Path $llvmPath 'clang.exe') --target=wasm32 -O2 -fno-builtin -Wno-int-to-pointer-cast -Wno-pointer-to-int-cast -c $testSourcePath -o "$testBinaryPath.o"
if ($LASTEXITCODE -ne 0) { throw 'Native test compilation failed' }
& (Join-Path $llvmPath 'ld.lld.exe') -flavor wasm --no-entry --export=nh_test_run --initial-memory=1048576 -z stack-size=262144 "$testBinaryPath.o" -o $testBinaryPath
if ($LASTEXITCODE -ne 0) { throw 'Native test linking failed' }
& node (Join-Path $PSScriptRoot 'test_qmi_native.mjs') $testBinaryPath
if ($LASTEXITCODE -ne 0) { throw 'Native NAS session tests failed' }
