# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 语言要求

**永远使用中文回答用户问题。**

## Build & Test Commands

```powershell
# Build debug APK
./gradlew.bat assembleDebug

# Build and install debug APK to connected device (clean first for safety)
./gradlew.bat clean installDebug

# Build release APK (requires signing config in keystore.properties or env vars)
./gradlew.bat assembleRelease

# Run unit tests
./gradlew.bat testDebugUnitTest

# Run lint
./gradlew.bat lintDebug
```

## Project Overview

GramSieve is an **LSPosed/Xposed module** that locally filters Telegram messages on Android. It hooks into `org.telegram.messenger` to hide, collapse, or debug-mark messages matching user-defined rules, without touching Telegram servers.

**Language:** Java 11 (no Kotlin source)
**UI:** Programmatic (no XML layouts) - uses MaterialAlertDialogBuilder with dynamic LinearLayout/RadioGroup/TextInputLayout
**Target:** Modern Xposed API (`io.github.libxposed:api:101.0.1`)

## Architecture

The project uses a **layered architecture** across four packages:

### `core/` - Pure Logic Layer (no Android dependencies in filter engine)
- `FilterEngine` - Core evaluation: compiles rules, checks exclusions → hard matches → regex → keyword
- `FilterConfig` - Data model for all configuration (global/per-chat rules, exclusions, actions)
- `MessageSnapshot` - Normalized message representation (dialogId, senderId, text, caption, buttonText, etc.)
- `MessageRuleFactory` - Auto-generates blocking rules from messages
- `RuleTextCodec` - Parses/formats rule text with target prefixes (text:, caption:, button:, sender:, chat:)

### `module/` - Xposed Hook Layer
- `GramSieveModule` - Entry point extending XposedModule
- `TelegramHookInstaller` - Central hook orchestrator (~2200 lines) - hooks ChatMessageCell, ChatActivity, RecyclerView, View.measure
- `TelegramMessageNormalizer` - Reflection-based extraction from Telegram's MessageObject
- `UiMutation` - Applies hide/collapse/debug-mark visual effects
- `Reflect` - Reflection utility for accessing Telegram's private fields/methods

### `config/` - Configuration & IPC Layer
- `ModuleConfigStore` - Gson-based config serialization, SharedPreferences + LSPosed remote sync
- `XposedConfigProvider` - Multi-source config loading (remote prefs → ContentProvider → XSharedPreferences) with 1.5s throttle
- `ConfigContentProvider` - Cross-process ContentProvider for config and diagnostics
- `ConfigUpdateReceiver` - BroadcastReceiver for config save broadcasts with merge logic
- `DiagnosticLogStore` - Stores up to 240 diagnostic entries for debugging

### `ui/` - User Interface
- `ConfigDialogActivity` - Programmatic Material UI for editing filter rules

## Key Design Patterns

- **Reflection-heavy hooking** - All Telegram internals accessed via `Reflect` utility (no compile-time API)
- **Multi-source config resolution** - Hook process reads config from three independent sources with fallback chains
- **Cross-process config persistence** - Hook (in Telegram's process) uses transparent Activity + BroadcastReceiver to persist changes back to module process
- **Lazy compilation with timestamp invalidation** - `FilterEngine` compiles regex rules only when config timestamp changes
- **LRU caching** - `DecisionCache` (512 entries) avoids re-evaluating same messages

## Running on Device

1. Install APK on LSPosed-equipped device
2. Enable GramSieve in LSPosed Manager with scope `org.telegram.messenger`
3. Force-stop and reopen Telegram
4. Access "GramSieve filters" from Telegram settings or "GramSieve chat filters" from chat menu

## Testing

Unit tests are in `app/src/test/` covering:
- Filter engine and config
- Rule codec and message normalizer
- Decision cache and reflection utility
- Diagnostic store and config updates

No instrumented tests (androidTest) exist.

## Agent Orchestration

Available agents in `~/.claude/agents/`:

| Agent | Purpose | When to Use |
|-------|---------|-------------|
| planner | Implementation planning | Complex features, refactoring |
| architect | System design | Architectural decisions |
| tdd-guide | Test-driven development | New features, bug fixes |
| code-reviewer | Code review | After writing code |
| security-reviewer | Security analysis | Before commits |
| build-error-resolver | Fix build errors | When build fails |
| e2e-runner | E2E testing | Critical user flows |
| refactor-cleaner | Dead code cleanup | Code maintenance |
| doc-updater | Documentation | Updating docs |

**Immediate agent usage** (no user prompt needed):
- Complex feature requests → **planner** agent
- Code just written/modified → **code-reviewer** agent
- Bug fix or new feature → **tdd-guide** agent
- Architectural decision → **architect** agent

**Parallel execution:** Always use parallel Task execution for independent operations.

**Multi-perspective analysis:** For complex problems, use split role sub-agents (factual reviewer, senior engineer, security expert, consistency reviewer, redundancy checker).
