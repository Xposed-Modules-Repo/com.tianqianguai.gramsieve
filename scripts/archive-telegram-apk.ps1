param(
    [string]$Device = "192.168.6.17:5555",
    [string]$Package = "org.telegram.messenger",
    [string]$ArchiveRoot = "local/telegram-apk-archive",
    [switch]$ForcePull,
    [switch]$ForceDecompile
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Invoke-External {
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    $output = & $Command @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Command $($Arguments -join ' ') failed with exit code $LASTEXITCODE.`n$output"
    }
    return $output
}

function Ensure-Device {
    param([string]$Serial)

    try {
        Invoke-External "adb" @("-s", $Serial, "get-state") | Out-Null
        return
    } catch {
        if ($Serial -match '^\d+\.\d+\.\d+\.\d+:\d+$') {
            Invoke-External "adb" @("connect", $Serial) | Out-Null
            Invoke-External "adb" @("-s", $Serial, "get-state") | Out-Null
            return
        }
        throw
    }
}

function ConvertTo-SafePathPart {
    param([string]$Value)
    return ($Value -replace '[^A-Za-z0-9._+-]', '_')
}

function Get-ApktoolInvocation {
    $apktool = Get-Command apktool -ErrorAction SilentlyContinue
    if (-not $apktool) {
        throw "apktool is required to create the decompiled archive."
    }

    if ($apktool.Source -match '\.bat$') {
        $toolDir = Split-Path -Parent $apktool.Source
        $jar = Get-ChildItem -LiteralPath $toolDir -Filter "apktool.jar" -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if (-not $jar) {
            $jar = Get-ChildItem -LiteralPath $toolDir -Filter "apktool*.jar" -File |
                Sort-Object Name -Descending |
                Select-Object -First 1
        }
        if (-not $jar) {
            throw "Could not find apktool jar next to $($apktool.Source)."
        }

        $java = if ($env:JAVA_HOME) {
            Join-Path $env:JAVA_HOME "bin/java.exe"
        } else {
            "java.exe"
        }

        return @{
            Command = $java
            Prefix = @(
                "-Xmx1024M",
                "-Duser.language=en",
                "-Dfile.encoding=UTF8",
                "-Djdk.util.zip.disableZip64ExtraFieldValidation=true",
                "-Djdk.nio.zipfs.allowDotZipEntry=true",
                "-jar",
                $jar.FullName
            )
        }
    }

    return @{
        Command = $apktool.Source
        Prefix = @()
    }
}

Ensure-Device $Device

$packageDump = Invoke-External "adb" @("-s", $Device, "shell", "dumpsys", "package", $Package)
$versionCodeMatch = [regex]::Match(($packageDump -join "`n"), 'versionCode=(\d+)')
$versionNameMatch = [regex]::Match(($packageDump -join "`n"), 'versionName=([^\s]+)')

if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "Could not read versionCode/versionName for $Package on $Device."
}

$versionCode = $versionCodeMatch.Groups[1].Value
$versionName = $versionNameMatch.Groups[1].Value
$versionKey = "$versionCode-$(ConvertTo-SafePathPart $versionName)"

$archiveBase = Join-Path $repoRoot $ArchiveRoot
$packageDir = Join-Path $archiveBase $Package
$versionDir = Join-Path $packageDir $versionKey
$apkDir = Join-Path $versionDir "apk"
$decompiledDir = Join-Path $versionDir "apktool-base"
$soundsDir = Join-Path $versionDir "message-sounds"

New-Item -ItemType Directory -Force -Path $apkDir, $soundsDir | Out-Null

$pmPathOutput = Invoke-External "adb" @("-s", $Device, "shell", "pm", "path", $Package)
$remoteApks = @(
    $pmPathOutput |
        ForEach-Object { $_.ToString().Trim() } |
        Where-Object { $_ -match '^package:' } |
        ForEach-Object { $_.Substring("package:".Length) }
)

if ($remoteApks.Count -eq 0) {
    throw "No APK paths found for $Package on $Device."
}

foreach ($remoteApk in $remoteApks) {
    $apkName = Split-Path $remoteApk -Leaf
    $localApk = Join-Path $apkDir $apkName
    if ((Test-Path $localApk) -and -not $ForcePull) {
        Write-Host "APK already archived: $localApk"
        continue
    }
    Write-Host "Pulling $remoteApk"
    Invoke-External "adb" @("-s", $Device, "pull", $remoteApk, $localApk) | Out-Host
}

$baseApk = Join-Path $apkDir "base.apk"
if (-not (Test-Path $baseApk)) {
    throw "Expected base APK was not archived: $baseApk"
}

if ((Test-Path $decompiledDir) -and $ForceDecompile) {
    $resolved = (Resolve-Path -LiteralPath $decompiledDir).Path
    $allowedRoot = (Resolve-Path -LiteralPath $versionDir).Path
    if (-not $resolved.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a decompile directory outside the version archive: $resolved"
    }
    Remove-Item -LiteralPath $decompiledDir -Recurse -Force
}

if (Test-Path (Join-Path $decompiledDir "AndroidManifest.xml")) {
    Write-Host "Decompile already archived: $decompiledDir"
} else {
    $apktoolInvocation = Get-ApktoolInvocation
    Write-Host "Decompiling base APK to $decompiledDir"
    Invoke-External $apktoolInvocation.Command ($apktoolInvocation.Prefix + @("d", $baseApk, "-o", $decompiledDir)) | Out-Host
}

Get-ChildItem -LiteralPath $soundsDir -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne "manifest.txt" } |
    Remove-Item -Force

$rawDirs = Get-ChildItem -LiteralPath (Join-Path $decompiledDir "res") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^raw' }

$audioFiles = @()
foreach ($rawDir in $rawDirs) {
    $audioFiles += Get-ChildItem -LiteralPath $rawDir.FullName -File |
        Where-Object { $_.Extension -match '^\.(ogg|mp3|wav|m4a)$' }
}

$messageSoundPattern = '(?i)(^sound_(in|out)$|message|msg|notification|notify|tone|incoming|outgoing)'
$messageSounds = @($audioFiles | Where-Object { $_.BaseName -match $messageSoundPattern })

if ($messageSounds.Count -eq 0) {
    $messageSounds = @($audioFiles)
}

foreach ($sound in $messageSounds) {
    Copy-Item -LiteralPath $sound.FullName -Destination (Join-Path $soundsDir $sound.Name) -Force
}

$metadata = @(
    "package=$Package",
    "device=$Device",
    "versionCode=$versionCode",
    "versionName=$versionName",
    "archive=$versionDir",
    "apkCount=$($remoteApks.Count)",
    "decompiled=$decompiledDir",
    "messageSounds=$($messageSounds.Count)",
    "",
    "apks:",
    ($remoteApks | ForEach-Object { "- $_" }),
    "",
    "message-sounds:",
    ($messageSounds | ForEach-Object { "- $($_.Name) <= $($_.FullName)" })
)

Set-Content -LiteralPath (Join-Path $versionDir "metadata.txt") -Value $metadata -Encoding UTF8
Set-Content -LiteralPath (Join-Path $soundsDir "manifest.txt") -Value $metadata -Encoding UTF8

Write-Host ""
Write-Host "Archived Telegram APK version: $versionKey"
Write-Host "Archive directory: $versionDir"
Write-Host "Message sounds: $soundsDir"
