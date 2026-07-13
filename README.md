# GramSieve

Telegram 本地增强 LSPosed 模块，提供消息过滤、宿主设置集成、浏览位置跳转、防撤回、防修改/编辑拦截、多版本编辑历史查看和原始媒体预览等能力。

An LSPosed module for local Telegram enhancements, including message filtering, host-settings integration, browsing position redirection, anti-recall, anti-edit/edit interception, multi-version edit-history viewing, and original media preview.

## 功能 Features

- **仅本地过滤** — 所有过滤在设备上完成，无网络请求，数据不离开手机
- **全局 + 单聊规则** — 全局设置宽泛规则，再针对特定聊天覆盖或排除
- **丰富的匹配目标** — 消息文字、媒体说明、内联按钮文字/链接、发送者名称/ID、聊天名称/ID
- **白名单优先** — 排除规则始终优先于过滤规则，适合管理员、公告或信任联系人
- **三种过滤动作** — 本地隐藏、本地折叠、调试标记（测试用）
- **宿主设置集成** — 在 Telegram 设置列表中提供 `GramSieve` 入口，配置页内嵌在宿主界面中，黑色背景并支持正常返回
- **消息标记与跳转** — 单击消息可标记位置，从右上角菜单一键跳回，每个聊天独立标记
- **浏览位置记忆** — 自动记录滚动位置，可一键跳转到上次浏览处
- **下载页全选** — Telegram 下载管理页面多选模式下支持一键全选
- **主动加载与防撤回防修改** — 后台和推送到达时主动加载消息，结合删除链路拦截和本地存储标记，尽量保留被撤回或修改的原始内容
- **多版本编辑历史** — 编辑历史按版本保存，并会从 Telegram 本地历史同步写入中补齐离线期间发生的编辑
- **编辑历史媒体查看** — 点击消息弹窗可查看编辑前内容，原始图片优先使用 Telegram 官方 PhotoViewer 并支持官方保存入口
- **持久化诊断日志** — 运行日志写入 app-specific 外部目录，避免依赖容易溢出的 logcat 缓冲区
- **模块冲突检测** — 识别常见 Telegram 增强模块；获得 root 授权后仅以只读方式核对 LSPosed 模块开关和 Telegram 作用域，再按防撤回、下载加速、Secret Media、去广告、Stories 和 UI 注入等重叠能力给出分级风险提示
- **双语界面** — 英文和简体中文，支持跟随系统

- **Local-only filtering** — all filtering happens on-device; no network requests, no data leaves your phone
- **Global + per-chat rules** — set broad filters globally, then override or exclude specific chats
- **Rich match targets** — message text, media captions, inline button labels/URLs, sender names/IDs, chat names/IDs
- **Whitelist wins first** — exclusion rules always override filter rules; use them for admins, notices, or trusted contacts
- **Three filter actions** — hide locally, collapse locally, or debug-mark (for testing)
- **Host settings integration** — adds a `GramSieve` row to Telegram settings and opens the configuration page inside the host UI with a black background and normal back navigation
- **Mark & jump** — tap a message to mark its position, jump back anytime from the menu; marks are per-chat
- **Browse position memory** — automatically tracks scroll position, one-tap jump to last viewed message
- **Download page select all** — select all loaded download items at once in Telegram's download manager
- **Anti-recall & anti-edit** — proactively loads messages in the background and when push updates arrive, combining delete-path interception and local-storage marking to preserve recalled or edited content where possible
- **Multi-version edit history** — stores edit history by version and recovers edits that arrive through Telegram local history-sync writes while the device was offline
- **Edit-history media viewer** — open original pre-edit content from the message popup; original images prefer Telegram's official PhotoViewer and official save flow
- **Persistent diagnostics** — runtime logs are written to app-specific external storage instead of relying on overflow-prone logcat buffers
- **Module conflict detection** — detects common Telegram enhancement modules; after root authorization, it uses read-only LSPosed database queries to confirm module switches and Telegram scope before reporting potential overlap across anti-recall, download acceleration, Secret Media, ad blocking, Stories, and UI injection
- **Bilingual UI** — English and Simplified Chinese, with system-follow option

## 规则写法 How Rules Work

GramSieve 会对消息文字、媒体说明、内联按钮文字/链接、发送者名称/ID、聊天名称/ID 进行标准化处理，然后逐行匹配规则。

GramSieve normalizes message text, media captions, inline button labels/URLs, sender names/IDs, and chat names/IDs, then matches rules line by line.

**关键词规则 Keyword rules:**

```
t.me/
buy now
sender:promo_bot
chat:airdrops
button:https://
caption:airdrop
```

**正则规则 Regex rules:**

```
https?://
sender:^(promo|deal)_bot$
button:https?://[^ ]+
```

**支持的前缀 Supported prefixes:**

| 前缀 Prefix | 检查目标 Checks |
|-------------|----------------|
| `text:` | 消息文字 Message text |
| `caption:` | 媒体说明 Media captions |
| `button:` | 按钮文字或链接 Button labels or URLs |
| `sender:` | 发送者名称或 ID Sender name or ID |
| `chat:` | 聊天名称或 ID Chat name or ID |
| *(无/none)* | 以上所有字段 All fields above |

在当前界面中，每个输入框已固定检查目标，通常不需要写前缀。

In the current UI, each input box is already target-specific, so prefixes are usually unnecessary.

## 入口 Entry Points

- **Telegram 设置列表** → `GramSieve`（宿主内嵌配置页）
- **聊天右上角三点菜单** → `聊天过滤规则` · `主动加载` · `跳转到上次浏览` · `跳转到标记位置`
- **单击某条消息** → `屏蔽此消息` · `标记此消息` · `编辑历史`
- **下载页面多选模式** → `全选` 按钮（一键选中所有已加载的下载项）

- **Telegram settings list** → `GramSieve` (host-embedded configuration page)
- **Chat top-right overflow menu** → `Chat filters` · `Proactive loading` · `Jump to last viewed` · `Jump to marked position`
- **Click a message** → `Block this message` · `Mark this message` · `Edit history`
- **Download page action mode** → `Select All` button (select all loaded download items at once)

规则存储在模块应用内，通过 LSPosed service bridge 同步给 Telegram 读取。

Rules are stored in the module app and synced to the LSPosed service bridge so Telegram can read them.

## 持久化日志 Persistent Logs

调试防撤回、主动加载或媒体缓存时，优先读取持久化日志。公共 `/sdcard/GramSieve` 路径已放弃，日志写入 app-specific 外部目录：

When debugging anti-recall, proactive loading, or media caching, read persistent logs first. The public `/sdcard/GramSieve` path is no longer used; logs are written to app-specific external storage:

```
adb -s <device> shell tail -n 300 /sdcard/Android/data/org.telegram.messenger/files/GramSieve/gramsieve.log
adb -s <device> shell tail -n 300 /sdcard/Android/data/com.tianqianguai.gramsieve/files/GramSieve/gramsieve.log
```

## 开发与诊断 Development & Diagnostics

适配新 Telegram 版本前，可以先归档设备上的 Telegram APK 与反编译材料：

Before adapting to a new Telegram version, archive the device APK and reverse-engineering materials:

```powershell
./scripts/archive-telegram-apk.ps1 -Device <device>
```

归档会写入 `local/telegram-apk-archive/`，该目录已加入 `.gitignore`，不会提交 APK 或 apktool 输出。

Archives are stored under `local/telegram-apk-archive/`, which is git-ignored so APKs and apktool output are not committed.

## 示例规则 Sample Rules

- [sample-global-rules.txt](examples/sample-global-rules.txt)
- [sample-chat-rules.txt](examples/sample-chat-rules.txt)
- [sample-config.json](examples/sample-config.json)

## 许可证 License

GramSieve 以 GNU General Public License v3.0 or later（GPL-3.0-or-later）发布。你可以复制、修改和分发本项目，但分发修改版或二进制版本时，需要遵守 GPL-3.0-or-later 的源码提供、许可证保留和同许可证分发要求。完整许可证文本见 [LICENSE](LICENSE)。

GramSieve is released under the GNU General Public License v3.0 or later (GPL-3.0-or-later). You may copy, modify, and distribute this project, but modified versions and binary distributions must follow the GPL-3.0-or-later requirements for source availability, license preservation, and same-license distribution. See [LICENSE](LICENSE) for the full license text.

## 参考与致谢 Acknowledgements

GramSieve 的部分反撤回和编辑历史设计参考了 [TeleVip-LSPosed](https://github.com/mustafa1dev/TeleVip-LSPosed) 对 Telegram 本地消息存储、删除标记和编辑历史捕获路径的公开研究。GramSieve 没有引入 TeleVip-LSPosed 源码；相关功能基于 GramSieve 自身的 hook、缓存、数据库和 UI 结构独立实现。

Parts of GramSieve's anti-recall and edit-history design were informed by the public research in [TeleVip-LSPosed](https://github.com/mustafa1dev/TeleVip-LSPosed) around Telegram local message storage, deletion flags, and edit-history capture paths. GramSieve does not include TeleVip-LSPosed source code; the related functionality is implemented independently using GramSieve's own hooks, cache, database, and UI structure.
