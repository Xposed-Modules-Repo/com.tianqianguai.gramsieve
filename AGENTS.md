# Repository Guidelines

## Project Structure & Module Organization
`app/` is the only Android module. Production code lives in `app/src/main/java/com/tianqianguai/gramsieve/` and is split by responsibility: `core/` for filtering logic and rule parsing, `config/` for preferences and providers (including `AntiRecallConfigStore` for anti-recall settings), `module/` for LSPosed/Telegram hooks (including `MessageCache`, `RecallDetector`, `BackgroundMessageLoader`, and `MessageDatabaseHelper` for the anti-recall subsystem), and `ui/` for the companion config activity. Android resources live in `app/src/main/res/`. Xposed metadata lives in `app/src/main/resources/META-INF/xposed/`. JVM tests are in `app/src/test/`, instrumented tests in `app/src/androidTest/`, and sample rule/config files in `examples/`.

## Build, Test, and Development Commands
- `./gradlew.bat assembleDebug` builds the debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew.bat testDebugUnitTest` runs fast JVM tests for filtering, config storage, and reflection helpers.
- `./gradlew.bat connectedDebugAndroidTest` runs instrumented tests on a connected device or emulator.
- `./gradlew.bat lintDebug` runs Android lint before review.
- `./gradlew.bat clean` clears generated outputs if Gradle state gets stale.

Use `./gradlew` instead of `./gradlew.bat` on non-Windows shells.

## Coding Style & Naming Conventions
Use 4-space indentation and keep source compatible with Java 11. Match the existing package split instead of adding broad utility layers. Use PascalCase for classes, camelCase for fields and methods, `UPPER_SNAKE_CASE` for constants, and `EXTRA_*` for intent extras. Keep pure logic classes small and deterministic; keep Telegram-specific hook code isolated under `module/`.

## Testing Guidelines
Use JUnit 4 for JVM tests and AndroidX JUnit/Espresso for instrumented coverage. Name test classes `*Test` and write behavior-focused method names such as `exclusionWinsOverKeywordRule`. Add or update unit tests whenever changes touch rule parsing, matching order, config persistence, or reflection behavior.

## Commit & Pull Request Guidelines
This checkout does not include `.git`, so follow the repo's documented intent-first style. Write commit subjects around why the change exists, then add Lore-style trailers when useful: `Constraint:`, `Rejected:`, `Confidence:`, `Scope-risk:`, `Tested:`, `Not-tested:`. Keep pull requests narrow, link the issue, summarize user-visible behavior changes, and list the verification commands you ran. Include screenshots only for UI changes such as the config dialog.

## Security & Configuration Tips
Keep LSPosed scope limited to `org.telegram.messenger`. Do not commit `build/`, `.gradle/`, generated APKs, or machine-specific files such as `local.properties`. Treat `examples/` as templates, not a place for personal rule sets or device data.
