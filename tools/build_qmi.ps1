param([string]$NdkPath = "$env:LOCALAPPDATA/Android/Sdk/ndk/26.3.11579264")
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$sourcePath = Join-Path $repoPath 'app/src/main/cpp/qmi/nh-qmi.c'
$assetPath = Join-Path $repoPath 'app/src/main/assets/qmi'
New-Item -ItemType Directory -Force -Path $assetPath | Out-Null
$clangPath = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
& $clangPath --target=aarch64-linux-android29 -fuse-ld=lld -O2 -nostdlib -static -fno-stack-protector -fno-builtin '-Wl,-e,_start' '-Wl,-z,max-page-size=16384' $sourcePath -o (Join-Path $assetPath 'nh-qmi')
if ($LASTEXITCODE -ne 0) { throw 'QMI helper compilation failed' }
(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assetPath 'nh-qmi')).Hash.ToLowerInvariant() | Set-Content -Encoding ascii -LiteralPath (Join-Path $assetPath 'nh-qmi.sha256')
$sourceBytes = [Text.Encoding]::UTF8.GetBytes([IO.File]::ReadAllText($sourcePath).Replace("`r`n", "`n"))
$sourceDigest = ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($sourceBytes))).Replace('-', '').ToLowerInvariant()
$sourceDigest | Set-Content -Encoding ascii -LiteralPath (Join-Path $assetPath 'nh-qmi.source.sha256')
Copy-Item -LiteralPath (Join-Path $repoPath 'app/src/main/cpp/qmi/LICENSE-GPL-3.0') -Destination (Join-Path $assetPath 'COPYING')
