param([string]$NdkPath = $env:ANDROID_NDK_HOME)
$ErrorActionPreference = 'Stop'
if (-not $NdkPath) { throw 'Pass -NdkPath <Android NDK path>.' }
$modulePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../qcom-efs'))
$env:ANDROID_NDK_HOME = $NdkPath
& (Join-Path $modulePath 'daemon/build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'EFS helper build failed.' }
$sources = Get-ChildItem -LiteralPath (Join-Path $modulePath 'daemon/src') -File | Where-Object Extension -in '.c','.h' | Sort-Object Name
$body = ($sources | ForEach-Object { $_.Name + [char]0 + [IO.File]::ReadAllText($_.FullName).Replace("`r`n", "`n") }) -join ''
$hash = [Security.Cryptography.SHA256]::Create()
$digest = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($body))).Replace('-', '').ToLowerInvariant()
$asset = Join-Path $modulePath 'src/main/assets/nh-efsd'
[IO.File]::WriteAllText("$asset.source.sha256", $digest + "`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText("$asset.sha256", (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant() + "`n", [Text.UTF8Encoding]::new($false))
Write-Output 'EFS native helper and source checksums updated.'
