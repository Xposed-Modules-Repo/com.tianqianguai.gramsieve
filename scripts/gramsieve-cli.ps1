param(
    [string]$Device = "192.168.6.17:5555",
    [ValidateSet("status", "settings", "modules", "logs")]
    [string]$Command = "status"
)

$ErrorActionPreference = "Stop"
$telegramPackage = "org.telegram.messenger"
$logPath = "/sdcard/Android/data/$telegramPackage/files/GramSieve/gramsieve.log"

$deviceStateOutput = @(& adb -s $Device get-state 2>$null)
$deviceState = if ($deviceStateOutput.Count -gt 0) {
    ([string]$deviceStateOutput[-1]).Trim()
} else {
    ""
}
if ($LASTEXITCODE -ne 0 -or $deviceState -ne "device") {
    throw "ADB device is unavailable: $Device"
}

function Get-SettingsState {
    $lines = & adb -s $Device shell tail -n 1000 $logPath
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $match = $lines | Select-String -SimpleMatch "SettingsState host=" | Select-Object -Last 1
    if ($null -eq $match) {
        return $null
    }
    return $match.Line
}

function Get-StateValue {
    param(
        [string]$State,
        [string]$Name
    )
    if ($State -match "(?:^| )$([regex]::Escape($Name))=([^ ]*)") {
        return $Matches[1]
    }
    return ""
}

switch ($Command) {
    "settings" {
        $state = Get-SettingsState
        if ([string]::IsNullOrWhiteSpace($state)) {
            throw "No SettingsState record. Open GramSieve settings in Telegram once."
        }
        [pscustomobject]@{
            Host = Get-StateValue $state "host"
            Source = Get-StateValue $state "source"
            Modules = Get-StateValue $state "modules"
            Fallbacks = Get-StateValue $state "fallbacks"
            Background = Get-StateValue $state "background"
            Card = Get-StateValue $state "card"
            Primary = Get-StateValue $state "primary"
            Secondary = Get-StateValue $state "secondary"
            Accent = Get-StateValue $state "accent"
            HeroStart = Get-StateValue $state "heroStart"
            HeroEnd = Get-StateValue $state "heroEnd"
            PrimaryCardContrast = Get-StateValue $state "primaryCard"
            SecondaryCardContrast = Get-StateValue $state "secondaryCard"
            HeroStartTextContrast = Get-StateValue $state "heroStartText"
            HeroEndTextContrast = Get-StateValue $state "heroEndText"
            Raw = $state
        }
    }
    "modules" {
        $state = Get-SettingsState
        if ([string]::IsNullOrWhiteSpace($state)) {
            throw "No module probe record. Open GramSieve settings in Telegram once."
        }
        $source = if ($state -match " source=([^ ]+)") { $Matches[1] } else { "unknown" }
        $modules = if ($state -match " modules=([^ ]*)") { $Matches[1] } else { "" }
        $fallbacks = if ($state -match " fallbacks=([^ ]*)") { $Matches[1] } else { "" }
        [pscustomobject]@{
            Device = $Device
            Source = $source
            Modules = $modules
            Fallbacks = $fallbacks
        }
    }
    "logs" {
        & adb -s $Device shell tail -n 300 $logPath
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to read GramSieve persistent log."
        }
    }
    default {
        $pidText = (& adb -s $Device shell pidof $telegramPackage).Trim()
        $packageDump = & adb -s $Device shell dumpsys package com.tianqianguai.gramsieve
        $versionName = (($packageDump | Select-String -Pattern "versionName=" | Select-Object -First 1).Line -replace ".*versionName=", "").Trim()
        $versionCode = (($packageDump | Select-String -Pattern "versionCode=" | Select-Object -First 1).Line -replace ".*versionCode=", "" -replace " minSdk.*", "").Trim()
        $debugApp = (& adb -s $Device shell settings get global debug_app).Trim()
        $waitDebugger = (& adb -s $Device shell settings get global wait_for_debugger).Trim()
        $providerNoise = 0
        $fatalAnr = 0
        if (-not [string]::IsNullOrWhiteSpace($pidText)) {
            $processLog = & adb -s $Device logcat -d --pid=$pidText -v brief
            $providerNoise = @($processLog | Select-String -SimpleMatch "Failed to find provider info for com.tianqianguai.gramsieve.config").Count
            $fatalAnr = @($processLog | Select-String -Pattern "FATAL EXCEPTION|ANR in").Count
        }
        [pscustomobject]@{
            Device = $Device
            TelegramPid = $pidText
            GramSieveVersion = "$versionName ($versionCode)"
            DebugApp = $debugApp
            WaitForDebugger = $waitDebugger
            ProviderNoise = $providerNoise
            FatalOrAnr = $fatalAnr
            SettingsState = Get-SettingsState
        }
    }
}
