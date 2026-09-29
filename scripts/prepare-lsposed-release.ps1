param(
    [ValidateSet("Auto", "Release", "Debug")]
    [string]$Variant = "Auto",
    [switch]$SkipClean
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$appBuildFile = Join-Path $repoRoot "app/build.gradle.kts"
$buildText = Get-Content -Raw $appBuildFile

$versionCodeMatch = [regex]::Match($buildText, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($buildText, 'versionName\s*=\s*"([^"]+)"')

if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "Could not read versionCode/versionName from app/build.gradle.kts."
}

$versionCode = $versionCodeMatch.Groups[1].Value
$versionName = $versionNameMatch.Groups[1].Value
$releaseTag = "$versionCode-$versionName"

$keystoreValues = @{}
$keystoreFile = Join-Path $repoRoot "keystore.properties"
if (Test-Path $keystoreFile) {
    foreach ($line in Get-Content $keystoreFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed.Split("=", 2)
        if ($parts.Count -eq 2) {
            $keystoreValues[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
}

function Get-SecretValue {
    param([string]$Name)

    $envValue = (Get-Item "Env:$Name" -ErrorAction SilentlyContinue).Value
    if (-not [string]::IsNullOrWhiteSpace($envValue)) {
        return $envValue
    }
    if ($keystoreValues.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace($keystoreValues[$Name])) {
        return $keystoreValues[$Name]
    }
    return $null
}

$releaseSigningReady = $true
foreach ($secretName in @(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
)) {
    if ([string]::IsNullOrWhiteSpace((Get-SecretValue $secretName))) {
        $releaseSigningReady = $false
        break
    }
}

$resolvedVariant = switch ($Variant) {
    "Auto" {
        if ($releaseSigningReady) { "Release" } else { "Debug" }
    }
    default { $Variant }
}

if ($resolvedVariant -eq "Release" -and -not $releaseSigningReady) {
    throw "Release signing is not configured. Copy keystore.properties.example to keystore.properties or provide the RELEASE_* environment variables."
}

if ($resolvedVariant -eq "Debug") {
    Write-Warning "Release signing is not configured. The script will package a debug-signed APK for local smoke testing only."
}

$gradleTasks = @()
if (-not $SkipClean) {
    $gradleTasks += "clean"
}
$gradleTasks += "lintDebug"
$gradleTasks += "testDebugUnitTest"
$gradleTasks += "assemble$resolvedVariant"

$gradleWrapper = if (Test-Path (Join-Path $repoRoot "gradlew.bat")) {
    Join-Path $repoRoot "gradlew.bat"
} else {
    Join-Path $repoRoot "gradlew"
}

Write-Host "Running Gradle tasks: $($gradleTasks -join ', ')"
& $gradleWrapper @gradleTasks
if ($LASTEXITCODE -ne 0) {
    throw "Gradle exited with code $LASTEXITCODE."
}

$variantLower = $resolvedVariant.ToLowerInvariant()
$sourceApk = Join-Path $repoRoot "app/build/outputs/apk/$variantLower/app-$variantLower.apk"
if (-not (Test-Path $sourceApk)) {
    throw "Expected APK was not found: $sourceApk"
}

$distDir = Join-Path $repoRoot "dist/lsposed"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

$artifactName = "GramSieve-v$versionName-$variantLower.apk"
$artifactPath = Join-Path $distDir $artifactName
Copy-Item -LiteralPath $sourceApk -Destination $artifactPath -Force

$sha256 = (Get-FileHash -Algorithm SHA256 $artifactPath).Hash.ToLowerInvariant()
$shaFile = Join-Path $distDir "$artifactName.sha256"
Set-Content -Path $shaFile -Value "$sha256  $artifactName"

$buildNote = if ($variantLower -eq "release") {
    "Signed release build created with the configured keystore."
} else {
    "Debug-signed build created because no release keystore is configured. Do not publish this build to end users."
}

$releaseNotes = @"
# GramSieve $versionName

- Local Telegram message filtering for LSPosed
- Recommended scope: org.telegram.messenger
- Tested Telegram baseline: 11.4.2 (release-11.4.2-5469)
- $buildNote

## Asset

- $artifactName
- SHA256: $sha256

## GitHub Release Fields

- Release title: $versionName
- Release tag: $releaseTag
"@

$releaseNotesPath = Join-Path $distDir "release-notes.md"
Set-Content -Path $releaseNotesPath -Value $releaseNotes

$publishInfo = @"
Repository name: com.tianqianguai.gramsieve
Repository description: GramSieve
Homepage/support URL: https://github.com/<owner>/com.tianqianguai.gramsieve/issues
Release title: $versionName
Release tag: $releaseTag
Artifact path: $artifactPath
SHA256 file: $shaFile
Release notes: $releaseNotesPath
"@

$publishInfoPath = Join-Path $distDir "publish-info.txt"
Set-Content -Path $publishInfoPath -Value $publishInfo

Write-Host ""
Write-Host "Packaged artifact: $artifactPath"
Write-Host "SHA256 file: $shaFile"
Write-Host "Release notes: $releaseNotesPath"
Write-Host "Publish info: $publishInfoPath"
