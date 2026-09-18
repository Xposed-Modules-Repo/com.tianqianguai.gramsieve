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

## 清理命令被拦截时

- 清理操作被工具或安全检查拦截时，直接向用户说明被拦截的操作和原因，并给出完整、可复制执行的 PowerShell 命令；不要只给命令片段或要求用户先进入工作区。
- 命令必须能在任意目录打开的 PowerShell 中执行：仓库、清理目标和脚本均使用明确的绝对路径；Git 命令使用 `git -C '<仓库绝对路径>' ...`；所需变量定义和前置检查放在同一代码块内，不依赖当前目录、先前会话变量或需要用户替换的占位符。
- 仅提供已获授权且已核实归属的清理目标，保留 Git-aware 清理、未提交内容保护和路径边界检查；不扩大删除范围，也不通过关闭或绕过安全检查继续执行。

## Release Notes & Publishing Workflow

- 版本命名跟随适配的 Telegram：`versionName` 必须与本次适配的 Telegram `versionName` 完全一致（例如 `12.10.2`）。`versionCode` 独立保持单调递增；同一 Telegram 版本的后续模块修订保持 `versionName`，递增 `versionCode` 并创建新 tag。
- Match the module `versionName` exactly to the Telegram version being adapted (for example, `12.10.2`). Keep Android `versionCode` monotonically increasing; subsequent module fixes for the same Telegram version retain `versionName`, increment `versionCode`, and use a new tag.

中文：

- 正式发布仓库固定为 `Xposed-Modules-Repo/com.tianqianguai.gramsieve`。所有 `gh release` 查询、创建、编辑和验证命令都必须显式使用这个仓库，不再使用旧名 `Xposed-Modules-Repo/Gramsieve`。
- 用户说“发版”时，默认创建一个新版本。只要上一个公开版本之后包含代码、行为、界面、配置或兼容性变化，就必须递增 `versionCode`，并将 `versionName` 设为适配的 Telegram 版本；不得沿用更早对“原位更新旧版本”的授权。只有用户在当前发布请求中明确指定现有 tag 并要求“不升版本号/原位更新”时，才允许覆盖旧 Release。
- 新版本使用 tag `<versionCode>-<versionName>`、标题 `GramSieve <versionName>`、资产名 `GramSieve-v<versionName>.apk`。发布前同时核对 `app/build.gradle.kts`、`output-metadata.json` 和 `aapt dump badging`，三处版本必须一致。
- 必须从最终已集成并推送的发布 commit 重新构建 APK，不得上传版本号修改前、合并前或其他工作树遗留的制品。创建 Release 前先 `fetch` 并确认本地 `main` 与远端没有意外分叉，再推送准确的发布 commit。
- 隔离 worktree 通常没有被 Git 忽略的 `keystore.properties`，而当前 Gradle 配置会在缺少正式签名时回退到 Debug 签名。因此 `assembleRelease` 成功不代表制品可发布。上传前必须运行 `apksigner verify --print-certs`：证书 SHA-256 必须是 GramSieve 正式指纹 `1d13359dd77d6da41d2d9aaa8fc099e92dd6e76861e0558d8e3bd822e5d6a055`，且不得是 `CN=Android Debug`。worktree 无正式签名时，先提交并合并，再从具备正式签名配置的主工作区重建。
- 发布门禁为：`testDebugUnitTest`、`lintDebug`、`assembleRelease`、`git diff --check`、APK 元数据检查、正式签名检查和 SHA-256 记录。仅修改发布文档时可跳过无关业务测试，但只要 APK 内容或版本变化就必须重新执行 APK 门禁。
- GitHub Release 正文只写更新日志，不写下载说明、校验说明或发布机制解释。
- Release notes 必须先中文后英文；中文区标题用 `## 更新日志`，英文区标题用 `## Changelog`。
- 更新日志只描述相对上一个公开版本新增或修正的内容，不把旧版本日志累计复制进新版本；保留适用的 API 102 兼容说明和 Star 提示。不得提及群内先行热修复或内部发布过程。
- 在 PowerShell 中不要用内联 `--notes "..."` 传包含 Markdown 反引号的内容；反引号会把 `a`、`v` 等字符转成控制字符并导致页面乱码。先写 UTF-8 notes 文件，再使用 `gh release edit/create --notes-file <file>`。
- 禁止在未获当前请求明确授权时使用 `gh release upload --clobber` 或 `gh release edit` 修改既有版本。若误覆盖旧 Release，必须先用发布前保存的原 APK 和原正文恢复旧版本，再创建正确的新版本；资产被重建后的历史下载计数无法恢复，必须如实报告。
- 创建新版本使用 `gh release create <tag> <apk> --repo Xposed-Modules-Repo/com.tianqianguai.gramsieve --title "GramSieve <versionName>" --notes-file <file> --target main --latest`。
- 发布后必须验证 `gh release list --repo Xposed-Modules-Repo/com.tianqianguai.gramsieve --limit 5`，并使用 `gh release view <tag> --json body,tagName,url,assets,targetCommitish,name,isDraft,isPrerelease` 核对 Latest 顺序、正文、tag、目标分支、资产名称/大小和 GitHub 返回的 SHA-256 digest。线上 digest 必须与本地 APK 一致。
- 如果还要在 Telegram 发布频道发版，必须先完成 GitHub Release 验证，再发送同一版本的 APK、对应 Release 链接和简明中文更新说明；频道文案中的版本号、兼容说明与 Star 提示必须与 GitHub Release 一致。

English:

- The canonical release repository is `Xposed-Modules-Repo/com.tianqianguai.gramsieve`. Every `gh release` read or mutation must specify it explicitly; do not use the obsolete `Xposed-Modules-Repo/Gramsieve` name.
- A user request to “release” means creating a new version by default. Any code, behavior, UI, configuration, or compatibility change since the last public release requires incrementing `versionCode` and setting `versionName` to the supported Telegram version. Authorization from an earlier task to update a release in place does not carry forward. Replacing an existing release is allowed only when the current request names the existing tag and explicitly asks to keep the version unchanged.
- New releases use tag `<versionCode>-<versionName>`, title `GramSieve <versionName>`, and asset name `GramSieve-v<versionName>.apk`. Before publishing, the versions in `app/build.gradle.kts`, `output-metadata.json`, and `aapt dump badging` must agree.
- Rebuild the APK from the final integrated and pushed release commit. Never publish an artifact produced before the version bump, before integration, or by a stale worktree. Fetch and check for unexpected divergence before pushing the exact release commit.
- Isolated worktrees usually lack the git-ignored `keystore.properties`, and the current Gradle setup falls back to debug signing when release credentials are absent. Therefore, a successful `assembleRelease` is not sufficient. Before upload, `apksigner verify --print-certs` must report the GramSieve production certificate SHA-256 `1d13359dd77d6da41d2d9aaa8fc099e92dd6e76861e0558d8e3bd822e5d6a055` and must not report `CN=Android Debug`. If the task worktree lacks production signing, commit and integrate first, then rebuild from the production-signing main checkout.
- The APK release gate is `testDebugUnitTest`, `lintDebug`, `assembleRelease`, `git diff --check`, APK metadata verification, production certificate verification, and recording the APK SHA-256. Documentation-only release edits may skip unrelated business tests, but any APK or version change requires the APK gate again.
- GitHub Release bodies should contain changelog entries only, not download instructions, verification details, or publishing-mechanism notes.
- Release notes must be bilingual with Chinese first and English second; use `## 更新日志` for Chinese and `## Changelog` for English.
- Describe only changes since the immediately preceding public release; do not accumulate prior release notes. Keep the API 102 compatibility note and Star request when applicable. Do not mention advance group hotfixes or internal publishing mechanics.
- Do not pass Markdown notes containing backticks through inline PowerShell `--notes "..."`; PowerShell can turn sequences such as `a` and `v` into control characters. Write a UTF-8 notes file and use `gh release edit/create --notes-file <file>`.
- Do not use `gh release upload --clobber` or `gh release edit` on an existing version without explicit authorization in the current request. If an old release is overwritten accidentally, restore its saved original APK and body before creating the correct new release. Recreating an asset resets its download count; report that fact accurately.
- Create a new release with `gh release create <tag> <apk> --repo Xposed-Modules-Repo/com.tianqianguai.gramsieve --title "GramSieve <versionName>" --notes-file <file> --target main --latest`.
- After publishing, run `gh release list --repo Xposed-Modules-Repo/com.tianqianguai.gramsieve --limit 5` and `gh release view <tag> --json body,tagName,url,assets,targetCommitish,name,isDraft,isPrerelease`. Verify the Latest ordering, body, tag, target, asset name/size, and GitHub SHA-256 digest against the local APK.
- For a Telegram channel release, complete GitHub verification first, then send the same-version APK, matching Release link, and concise Chinese changelog. The version, compatibility note, and Star request must match GitHub.

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

## Telegram APK Reverse-Engineering Archive
```powershell
./scripts/archive-telegram-apk.ps1 -Device 192.168.6.17:5555
```

- Use this script before decompiling Telegram from a device. It pulls `org.telegram.messenger`, reads `versionCode`/`versionName`, archives all split APKs, decompiles `base.apk`, and extracts message notification sounds.
- Versioned archive path: `local/telegram-apk-archive/org.telegram.messenger/<versionCode>-<versionName>/`.
- The archive is intentionally git-ignored. Do not commit APKs, apktool output, or extracted Telegram assets.
- Do not delete an existing version archive after decompilation. Reuse it on later investigations; only pass `-ForceDecompile` when you intentionally want to rebuild that local cache.

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

## Telegram 发布文案

- 用户说“发版”时，发布完成后的最终回复默认同时提供可直接复制的 Telegram 发布文案和该版本安装包地址，无需用户再次索取。安装包地址应包含本地正式签名 APK 的绝对路径链接及 GitHub Release 资产的直接下载链接；文案、链接和安装包版本必须与本次已验证的 Release 一致。提供文案不等于获准代发 Telegram 消息。
- Telegram 发布文案不包含 Release 地址、API 兼容说明、旧版本提示或类似安装兼容性说明；只保留版本更新内容，并附项目地址 `https://github.com/Xposed-Modules-Repo/com.tianqianguai.gramsieve` 邀请用户点 Star。安装包的本地链接和直接下载地址在文案之外单独提供。
