param(
    [string]$Device = "192.168.6.17:5555",
    [ValidateSet(
        "status", "settings", "modules", "logs", "ping", "state", "config",
        "feature", "fallback", "anti-recall", "edit-history", "load", "mark",
        "read-position", "message", "cleanup", "ui"
    )]
    [string]$Command = "status",
    [string]$Action = "",
    [string]$Name = "",
    [string]$Value = "",
    [string]$DialogId = "",
    [string]$AccountId = "",
    [string]$MessageId = "",
    [int]$Limit = 50,
    [string]$Preview = "",
    [string]$Path = ""
)

<#
Examples:
  ./scripts/gramsieve-cli.ps1 -Command state
  ./scripts/gramsieve-cli.ps1 -Command config -Action export -Path ./gramsieve-config.json
  ./scripts/gramsieve-cli.ps1 -Command config -Action apply -Path ./gramsieve-config.json
  ./scripts/gramsieve-cli.ps1 -Command feature -Action set -Name show_message_id -Value true
  ./scripts/gramsieve-cli.ps1 -Command fallback -Action set -Name TAUXILIARY -Value true
  ./scripts/gramsieve-cli.ps1 -Command anti-recall -Action set -DialogId -100123 -Value true
  ./scripts/gramsieve-cli.ps1 -Command edit-history -Action set -Name dialog -DialogId -100123 -Value record
  ./scripts/gramsieve-cli.ps1 -Command load -Action trigger
  ./scripts/gramsieve-cli.ps1 -Command mark -Action set -DialogId -100123 -MessageId 42 -Preview "important"
  ./scripts/gramsieve-cli.ps1 -Command read-position -Action get -DialogId -100123
  ./scripts/gramsieve-cli.ps1 -Command message -Action recalled -DialogId -100123 -Limit 50
  ./scripts/gramsieve-cli.ps1 -Command cleanup -Action set -DialogId -100123 -Value true
  ./scripts/gramsieve-cli.ps1 -Command ui -Action jump-mark -DialogId -100123
#>

$ErrorActionPreference = "Stop"
$telegramPackage = "org.telegram.messenger"
$cliAction = "com.tianqianguai.gramsieve.action.CLI"
$logPath = "/sdcard/Android/data/$telegramPackage/files/GramSieve/gramsieve.log"

$deviceStateOutput = @(& adb -s $Device get-state)
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

function Invoke-HostCli {
    param(
        [string]$HostCommand,
        [hashtable]$Extras = @{}
    )
    $adbArgs = @(
        "-s", $Device, "shell", "am", "broadcast",
        "-a", $cliAction,
        "-p", $telegramPackage,
        "--receiver-registered-only",
        "--es", "command", $HostCommand
    )
    foreach ($key in ($Extras.Keys | Sort-Object)) {
        $extraValue = [string]$Extras[$key]
        if (-not [string]::IsNullOrWhiteSpace($extraValue)) {
            $adbArgs += @("--es", [string]$key, $extraValue)
        }
    }
    $broadcastOutput = @(& adb @adbArgs)
    if ($LASTEXITCODE -ne 0) {
        throw "Telegram host CLI broadcast failed: $($broadcastOutput -join [Environment]::NewLine)"
    }
    $resultLine = $broadcastOutput | Select-String -Pattern 'Broadcast completed: result=(-?\d+), data="([^"]*)"' | Select-Object -Last 1
    if ($null -eq $resultLine) {
        throw "Telegram host CLI did not respond. Restart Telegram after installing the current GramSieve build."
    }
    $encoded = $resultLine.Matches[0].Groups[2].Value
    try {
        $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
        $response = $json | ConvertFrom-Json
    } catch {
        throw "Telegram host CLI returned an invalid response: $encoded"
    }
    if (-not $response.ok) {
        throw "Telegram host CLI rejected '$HostCommand': $($response.error)"
    }
    return $response
}

function Get-CommonExtras {
    $extras = @{}
    if (-not [string]::IsNullOrWhiteSpace($Name)) { $extras["name"] = $Name }
    if (-not [string]::IsNullOrWhiteSpace($Value)) { $extras["value"] = $Value }
    if (-not [string]::IsNullOrWhiteSpace($DialogId)) { $extras["dialog_id"] = $DialogId }
    if (-not [string]::IsNullOrWhiteSpace($AccountId)) { $extras["account_id"] = $AccountId }
    if (-not [string]::IsNullOrWhiteSpace($MessageId)) { $extras["message_id"] = $MessageId }
    if ($Limit -gt 0) { $extras["limit"] = [string]$Limit }
    if (-not [string]::IsNullOrWhiteSpace($Preview)) { $extras["preview"] = $Preview }
    return $extras
}

switch ($Command) {
    "settings" {
        $state = Get-SettingsState
        if ([string]::IsNullOrWhiteSpace($state)) {
            throw "No SettingsState record. Use -Command state for host configuration state."
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
        Invoke-HostCli "modules.scan"
    }
    "logs" {
        & adb -s $Device shell tail -n 300 $logPath
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to read GramSieve persistent log."
        }
    }
    "ping" { Invoke-HostCli "ping" }
    "state" { Invoke-HostCli "state" }
    "config" {
        $configAction = if ([string]::IsNullOrWhiteSpace($Action)) { "get" } else { $Action.ToLowerInvariant() }
        if ($configAction -eq "get" -or $configAction -eq "export") {
            $response = Invoke-HostCli "config.get"
            if (-not [string]::IsNullOrWhiteSpace($Path)) {
                $resolvedPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
                [IO.File]::WriteAllText($resolvedPath, [string]$response.configJson, (New-Object Text.UTF8Encoding($false)))
                [pscustomobject]@{
                    Ok = $true
                    Command = "config.export"
                    Path = $resolvedPath
                    UpdatedAtEpochMs = ($response.configJson | ConvertFrom-Json).updatedAtEpochMs
                }
            } else {
                $response.configJson | ConvertFrom-Json
            }
        } elseif ($configAction -eq "set" -or $configAction -eq "apply" -or $configAction -eq "import") {
            if ([string]::IsNullOrWhiteSpace($Path)) {
                throw "-Path is required for config apply."
            }
            $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
            $configJson = [IO.File]::ReadAllText($resolvedPath)
            $configB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($configJson))
            Invoke-HostCli "config.set" @{ config_b64 = $configB64 }
        } else {
            throw "Unsupported config action: $Action"
        }
    }
    "feature" {
        $featureAction = if ([string]::IsNullOrWhiteSpace($Action)) { "list" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "feature.$featureAction" (Get-CommonExtras)
    }
    "fallback" {
        $fallbackAction = if ([string]::IsNullOrWhiteSpace($Action)) { "list" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "fallback.$fallbackAction" (Get-CommonExtras)
    }
    "anti-recall" {
        $antiRecallAction = if ([string]::IsNullOrWhiteSpace($Action)) { "list" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "anti-recall.$antiRecallAction" (Get-CommonExtras)
    }
    "edit-history" {
        $editHistoryAction = if ([string]::IsNullOrWhiteSpace($Action)) { "get" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "edit-history.$editHistoryAction" (Get-CommonExtras)
    }
    "load" {
        $loadAction = if ([string]::IsNullOrWhiteSpace($Action)) { "trigger" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "load.$loadAction" (Get-CommonExtras)
    }
    "mark" {
        $markAction = if ([string]::IsNullOrWhiteSpace($Action)) { "list" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "mark.$markAction" (Get-CommonExtras)
    }
    "read-position" {
        $readPositionAction = if ([string]::IsNullOrWhiteSpace($Action)) { "get" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "read-position.$readPositionAction" (Get-CommonExtras)
    }
    "message" {
        $messageAction = if ([string]::IsNullOrWhiteSpace($Action)) { "get" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "message.$messageAction" (Get-CommonExtras)
    }
    "cleanup" {
        $cleanupAction = if ([string]::IsNullOrWhiteSpace($Action)) { "get" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "cleanup.$cleanupAction" (Get-CommonExtras)
    }
    "ui" {
        $uiAction = if ([string]::IsNullOrWhiteSpace($Action)) { "state" } else { $Action.ToLowerInvariant() }
        Invoke-HostCli "ui.$uiAction" (Get-CommonExtras)
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
        $bridgeReady = $false
        $bridgeProtocol = $null
        try {
            $ping = Invoke-HostCli "ping"
            $bridgeReady = $true
            $bridgeProtocol = $ping.protocolVersion
        } catch {
            $bridgeReady = $false
        }
        [pscustomobject]@{
            Device = $Device
            TelegramPid = $pidText
            GramSieveVersion = "$versionName ($versionCode)"
            CliBridgeReady = $bridgeReady
            CliProtocol = $bridgeProtocol
            DebugApp = $debugApp
            WaitForDebugger = $waitDebugger
            ProviderNoise = $providerNoise
            FatalOrAnr = $fatalAnr
            SettingsState = Get-SettingsState
        }
    }
}
