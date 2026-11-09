param(
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [Parameter(Mandatory = $true)][string]$Jdk
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $Jdk
$androidJar = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
$d8 = Join-Path $AndroidSdk 'build-tools/36.0.0/d8.bat'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build = Join-Path $repoRoot 'build'
$staging = Join-Path $build 'coloros-wifi-sense-work'
$sourceNames = @('src/ColorOsWifiSense.java', 'src/WifiDisconnectPolicy.java', 'src/WifiFluidSession.java')
$sources = @($sourceNames | ForEach-Object { Join-Path $PSScriptRoot $_ })
$moduleProp = Get-Content (Join-Path $PSScriptRoot 'module.prop') -Encoding UTF8
$versionLine = $moduleProp | Where-Object { $_ -match '^version=v[0-9.]+$' } | Select-Object -First 1
if (!$versionLine) { throw 'Module version missing from module.prop' }
$version = $versionLine.Substring('version='.Length)
$zipPath = Join-Path $build "coloros-wifi-sense-$version.zip"

New-Item -ItemType Directory -Path $staging -Force | Out-Null
Get-ChildItem -LiteralPath $staging -Filter '*.class' -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
& (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 --release 8 -classpath $androidJar -d $staging $sources
if ($LASTEXITCODE -ne 0) { throw 'Module Java compilation failed' }
$coreClasses = @(Get-ChildItem $staging -Filter '*.class' | Where-Object { $_.Name -like 'ColorOsWifiSense*' -or $_.Name -like 'WifiDisconnectPolicy*' } | ForEach-Object FullName)
& $d8 --min-api 29 --lib $androidJar --output $staging $coreClasses
if ($LASTEXITCODE -ne 0) { throw 'Module DEX generation failed' }
$fluidStaging = Join-Path $staging 'fluid'
New-Item -ItemType Directory -Path $fluidStaging -Force | Out-Null
& $d8 --min-api 29 --lib $androidJar --output $fluidStaging @(Get-ChildItem $staging -Filter 'WifiFluidSession*.class' | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { throw 'Fluid DEX generation failed' }

Add-Type -AssemblyName System.IO.Compression
$utf8 = [Text.UTF8Encoding]::new($false)
$stream = [IO.File]::Create($zipPath)
try {
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $files = @('module.prop', 'customize.sh', 'service.sh', 'action.sh', 'settings.sh', 'demo.sh', 'README.md') + $sourceNames
        foreach ($name in $files + 'LICENSE' + 'wifi-sense.dex' + 'wifi-fluid.dex') {
            $entry = $zip.CreateEntry($name)
            $entry.ExternalAttributes = if ($name.EndsWith('.sh')) { 0x81ED0000 } else { 0x81A40000 }
            if ($name -eq 'wifi-sense.dex') {
                $bytes = [IO.File]::ReadAllBytes((Join-Path $staging 'classes.dex'))
            } elseif ($name -eq 'wifi-fluid.dex') {
                $bytes = [IO.File]::ReadAllBytes((Join-Path $fluidStaging 'classes.dex'))
            } else {
                $path = if ($name -eq 'LICENSE') { Join-Path $repoRoot $name } else { Join-Path $PSScriptRoot $name }
                $bytes = $utf8.GetBytes([IO.File]::ReadAllText($path).Replace("`r`n", "`n"))
            }
            $output = $entry.Open()
            try { $output.Write($bytes, 0, $bytes.Length) } finally { $output.Dispose() }
        }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }

Get-Item $zipPath
