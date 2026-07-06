# Repository Guidelines

## Project Structure & Module Organization
`app/` is the only Android module. Production code in `app/src/main/java/com/tianqianguai/gramsieve/`:
- `core/` — Filter logic, rule parsing, no Android deps in FilterEngine
- `config/` — Preferences, providers, logging (`AntiRecallConfigStore` for anti-recall)
- `module/` — LSPosed/Telegram hooks: `GramSieveModule` (entry), `TelegramHookInstaller` (~4300 lines, main hook orchestrator), `RecallDetector`, `BackgroundMessageLoader`, `MessageCache`, `MessageDatabaseHelper`, `Reflect`
- `ui/` — Config dialog (programmatic Material UI, no XML layouts)

## Build, Test, and Development Commands
```powershell
./gradlew.bat assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat testDebugUnitTest      # JVM tests
./gradlew.bat lintDebug              # Android lint
./gradlew.bat connectedDebugAndroidTest  # Instrumented tests (requires device)
```

## Release Notes & Publishing Workflow
中文：
- GitHub Release 正文只写更新日志，不写下载说明、校验说明或发布机制解释。
- Release notes 必须先中文后英文；中文区标题用 `## 更新日志`，英文区标题用 `## Changelog`。
- 在 PowerShell 中不要用内联 `--notes "..."` 传包含 Markdown 反引号的内容；反引号会把 `a`、`v` 等字符转成控制字符并导致页面乱码。先写 UTF-8 notes 文件，再使用 `gh release edit/create --notes-file <file>`。
- Xposed-Modules-Repo 上传原始 APK 资产时可能会把 release tag 自动规范化成 `versionCode-versionName`，例如 `2-0.2.0`。如果需要用户可见 tag 保持 `v0.2.0`，发布资产使用 zip 包承载 APK，并在发布后核对 release 列表。
- 发布后必须验证：`gh release list --repo Xposed-Modules-Repo/Gramsieve --limit 5`，以及 `gh release view <tag> --json body,tagName,url`。

English:
- GitHub Release bodies should contain changelog entries only, not download instructions, verification details, or publishing-mechanism notes.
- Release notes must be bilingual with Chinese first and English second; use `## 更新日志` for Chinese and `## Changelog` for English.
- Do not pass Markdown notes containing backticks through inline PowerShell `--notes "..."`; PowerShell can turn sequences such as `a` and `v` into control characters. Write a UTF-8 notes file and use `gh release edit/create --notes-file <file>`.
- Raw APK assets in Xposed-Modules-Repo may normalize the visible release tag to `versionCode-versionName`, such as `2-0.2.0`. If the public tag must remain `v0.2.0`, upload a zip that contains the APK and verify the release list afterward.
- After publishing, verify with `gh release list --repo Xposed-Modules-Repo/Gramsieve --limit 5` and `gh release view <tag> --json body,tagName,url`.

## Device Connection & Log Capture
```powershell
adb connect <ip>:5555               # WiFi ADB
adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# Persistent log capture is the source of truth.
# Always read persistent files first when diagnosing behavior after a repro; logcat buffers overflow easily.
# 日志写入 app-specific 外部目录，不再使用 /sdcard/GramSieve 公共路径，避免 scoped-storage 权限问题。
adb -s <ip>:5555 shell cat /sdcard/Android/data/org.telegram.messenger/files/GramSieve/gramsieve.log
adb -s <ip>:5555 shell tail -n 300 /sdcard/Android/data/org.telegram.messenger/files/GramSieve/gramsieve.log
adb -s <ip>:5555 shell grep -E "Anti-recall|RecallDetector|BackgroundMessage|MediaCache" /sdcard/Android/data/org.telegram.messenger/files/GramSieve/gramsieve.log
adb -s <ip>:5555 shell cat /sdcard/Android/data/com.tianqianguai.gramsieve/files/GramSieve/gramsieve.log

# Pull only when you need to archive/share the whole log locally.
adb -s <ip>:5555 pull /sdcard/Android/data/org.telegram.messenger/files/GramSieve/gramsieve.log ./gramsieve-telegram.log
adb -s <ip>:5555 pull /sdcard/Android/data/com.tianqianguai.gramsieve/files/GramSieve/gramsieve.log ./gramsieve-module.log

# 实时查看日志 only for live observation; do not rely on buffered logcat for historical diagnosis.
adb -s <ip>:5555 logcat -s GramSieve:I

# Buffered logcat is a fallback when the persistent file is unavailable or for very recent live output.
adb -s <ip>:5555 logcat -d | findstr "GramSieve" | findstr "Anti-recall\|RecallDetector\|BackgroundMessage"
```

## Telegram Hook Architecture

### Xposed Module Lifecycle
1. `GramSieveModule.onPackageLoaded` — fires when Telegram loads
2. `GramSieveModule.onPackageReady` — calls `TelegramHookInstaller.install()`
3. `install()` hooks ChatMessageCell, ChatActivity, RecyclerView, etc.

### Deferred Initialization Pattern
`ActivityThread.currentApplication()` returns null during `install()`. Anti-recall components defer initialization until menu injection when chat context is available:
```java
// In injectAntiRecallMenu():
if (backgroundMessageLoader == null) {
    initAntiRecallFromChat(chatActivity);  // uses chat context
}
```

### Key Telegram Internal Classes (via Reflection)
- `MessageObject` — wrapper with `messageText`, `caption`, `messageOwner` fields
- `MessageObject.messageOwner` — TLRPC.TL_message with `message`, `media`, `from_id`, `peer_id`
- `TLRPC.TL_updateEditChannelMessage` — edit update (has `message` field, NOT `edit_message`)
- `TLRPC.TL_updateDeleteChannelMessages` — delete update (has `channel_id` + `messages` ArrayList)
- `MessagesController.processUpdateArray(ArrayList)` — receives all updates
- `MessagesController.loadMessages(...)` — 20-param method for loading chat history
- `ChatMessageCell.setMessageObject(...)` — binds MessageObject to cell

### Hook Timing
- `handleMessageBinding` runs AFTER `chain.proceed()` — cell already rendered
- Modifying `messageObject.messageText` or `messageOwner.message` before `chain.proceed()` does NOT prevent Telegram from displaying edited content (cell reads from internal copies)
- `setBackgroundColor()` on cell gets overwritten by Telegram's own rendering
- `post()` callbacks and child view additions work but may be overridden by Telegram's draw cycle

### Anti-Recall Subsystem
```
AntiRecallConfigStore → SharedPreferences (per-chat toggle)
BackgroundMessageLoader → periodic loadMessages via ScheduledExecutorService
RecallDetector → hooks processUpdateArray/deleteMessages/editMessage
MessageCache → LRU (1000) + SQLite (MessageDatabaseHelper)
```
- Enabled chats persisted in SharedPreferences, auto-loaded on startup
- Messages cached when first displayed via `handleMessageBinding`
- Edits detected via `TL_updateEditChannelMessage` class name check

## Coding Style & Naming Conventions
Java 11, 4-space indent. PascalCase classes, camelCase fields/methods, `UPPER_SNAKE_CASE` constants. Keep hook code in `module/`, pure logic in `core/`.

## Testing
JUnit 4 for JVM tests. Mockito added for SharedPreferences mocking. Test classes named `*Test`. Instrumented tests in `app/src/androidTest/`.

## Commit Style
Intent-first. Lore-style trailers: `Constraint:`, `Tested:`, `Not-tested:`.

## Security
LSPosed scope limited to `org.telegram.messenger`. Do not commit `build/`, `.gradle/`, APKs, `local.properties`.
