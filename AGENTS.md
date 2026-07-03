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

## Device Connection & Log Capture
```powershell
adb connect <ip>:5555               # WiFi ADB
adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# Persistent log capture is the source of truth.
# Always read this file first when diagnosing behavior after a repro; logcat buffers overflow easily.
# 日志文件自动写入 /sdcard/GramSieve/gramsieve.log
adb -s <ip>:5555 shell cat /sdcard/GramSieve/gramsieve.log
adb -s <ip>:5555 shell tail -n 300 /sdcard/GramSieve/gramsieve.log
adb -s <ip>:5555 shell grep -E "Anti-recall|RecallDetector|BackgroundMessage|MediaCache" /sdcard/GramSieve/gramsieve.log

# Pull only when you need to archive/share the whole log locally.
adb -s <ip>:5555 pull /sdcard/GramSieve/gramsieve.log ./gramsieve.log

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
