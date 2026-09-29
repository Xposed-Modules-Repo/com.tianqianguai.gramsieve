# LSPosed Publish Guide

This project is prepared for publication as package `com.tianqianguai.gramsieve`.

## Required GitHub Repository Settings

- Repository name: `com.tianqianguai.gramsieve`
- Description: `GramSieve`
- Visibility: Public
- Homepage / website: your support URL, usually `https://github.com/<owner>/com.tianqianguai.gramsieve/issues`

## Required Root Files

- `README.md`
- `SUMMARY`
- `SCOPE`
- Optional `SOURCE_URL` if you keep the public source code in a different repository than the LSPosed listing repository

If this repository itself is the public source repository, you can usually skip `SOURCE_URL`.

## One-Time Signing Setup

1. Copy `keystore.properties.example` to `keystore.properties`.
2. Replace the placeholder values with a private release keystore path and passwords.
3. Keep `keystore.properties` and the keystore file out of version control.

The Gradle release build falls back to debug signing when no release keystore is configured so local verification can still run. Do not publish a debug-signed build to users.

## Build a Release Package

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/prepare-lsposed-release.ps1
```

The script will:

- run `lintDebug`, `testDebugUnitTest`, and the APK build
- choose a release build when signing is configured, otherwise fall back to debug
- copy the APK into `dist/lsposed/`
- generate a SHA-256 file
- generate `release-notes.md` and `publish-info.txt`

## GitHub CLI Publish Flow

If the repository has not been created yet:

```powershell
git init -b main
git add .
git commit -m "Prepare GramSieve for LSPosed publication"
gh repo create com.tianqianguai.gramsieve --public --source . --remote origin --push --description "GramSieve"
```

After the repository exists, set the homepage to your support URL:

```powershell
gh repo edit --homepage "https://github.com/<owner>/com.tianqianguai.gramsieve/issues"
```

Create the GitHub release after `scripts/prepare-lsposed-release.ps1` finishes:

```powershell
gh release create <versionCode-versionName> <artifact-path> --title <versionName> --notes-file dist/lsposed/release-notes.md
```

Read `dist/lsposed/publish-info.txt` after the script finishes; it contains the exact tag and artifact path for the current build.

For the current `0.1.0` release, the command becomes:

```powershell
gh release create 1-0.1.0 dist/lsposed/GramSieve-v0.1.0-release.apk --title 0.1.0 --notes-file dist/lsposed/release-notes.md
```

If the script had to fall back to a debug build, replace the asset path with the generated debug artifact after you intentionally decide to use it for smoke testing.

## LSPosed Submission

Once the public repository and GitHub release are live:

1. Open [modules.lsposed.org/submission](https://modules.lsposed.org/submission/).
2. Submit the GitHub repository URL.
3. Wait for the automation to validate the package name, release asset, and metadata files.

The expected release format for this project is:

- Release title: `0.1.0`
- Release tag: `1-0.1.0`

## Recommended Final Checklist

- The repository is public.
- The repository name matches the package name exactly.
- `README.md`, `SUMMARY`, and `SCOPE` are present at the repo root.
- The release contains an installable APK.
- The homepage points to a support or discussion URL.
- The scope is limited to `org.telegram.messenger`.
- The release notes mention the tested Telegram baseline.
