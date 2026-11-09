param(
    [string]$OutputPath = (Join-Path $PSScriptRoot '../src/main/assets/nh-efsd'),
    [int]$Api = 26
)

$ErrorActionPreference = 'Stop'
$ndkPath = $env:ANDROID_NDK_HOME
if (-not $ndkPath) { $ndkPath = $env:ANDROID_NDK_ROOT }
if (-not $ndkPath) { throw 'Set ANDROID_NDK_HOME to your Android NDK directory.' }
$toolchain = Join-Path $ndkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$compiler = Join-Path $toolchain 'clang.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw "NDK compiler not found: $compiler" }

$OutputPath = [IO.Path]::GetFullPath($OutputPath)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($OutputPath)) | Out-Null
$sourceFiles = @('util.c', 'diag.c', 'efs2.c', 'main.c') | ForEach-Object {
    Join-Path $PSScriptRoot "src/$_"
}
# Match build.sh: dynamic libc linkage is required on Android 8 through 11.
& $compiler "--target=aarch64-linux-android$Api" -Os -flto `
    -fno-strict-aliasing -fstack-protector-strong -Wall -Wextra -Wno-unused-parameter `
    -o $OutputPath @sourceFiles
if ($LASTEXITCODE -ne 0) { throw "Native helper compilation failed: $LASTEXITCODE" }
& (Join-Path $toolchain 'llvm-strip.exe') $OutputPath
if ($LASTEXITCODE -ne 0) { throw "Native helper stripping failed: $LASTEXITCODE" }
Write-Output "Built $OutputPath ($((Get-Item -LiteralPath $OutputPath).Length) bytes)"
