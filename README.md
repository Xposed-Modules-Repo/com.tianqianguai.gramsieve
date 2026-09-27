<p align="center">
  <img src="assets/gramsieve-logo.png" width="168" height="168" alt="GramSieve paper plane icon">
</p>

<h1 align="center">GramSieve</h1>

<p align="center">
  面向 Telegram 官方 Play 与官网 Android 客户端（<code>org.telegram.messenger</code> / <code>org.telegram.messenger.web</code>）的增强 LSPosed/Xposed 模块
  <br>
  An enhancement LSPosed/Xposed module for the official Telegram Play and website Android clients
</p>

<p align="center">
  <a href="https://github.com/Xposed-Modules-Repo/com.tianqianguai.gramsieve/stargazers"><img src="https://img.shields.io/github/stars/Xposed-Modules-Repo/com.tianqianguai.gramsieve?style=for-the-badge&amp;logo=github&amp;label=Star" alt="GitHub Stars"></a>
  <a href="https://t.me/Gramsieve_Offical"><img src="https://img.shields.io/badge/Telegram-Official_Group-26A5E4?style=for-the-badge&amp;logo=telegram&amp;logoColor=white" alt="Telegram Official Group"></a>
  <a href="https://t.me/zhongjitianqianguai3"><img src="https://img.shields.io/badge/Telegram-Release_Channel-26A5E4?style=for-the-badge&amp;logo=telegram&amp;logoColor=white" alt="Telegram Release Channel"></a>
</p>

<p align="center">
  如果 GramSieve 对你有帮助，欢迎点一个 Star 支持项目 ⭐
  <br>
  If GramSieve helps you, please consider leaving a Star.
</p>

## 功能

- **仅本地过滤** — 所有过滤在设备上完成，无网络请求，数据不离开手机
- **全局 + 单聊规则** — 全局设置宽泛规则，再针对特定聊天覆盖或排除
- **丰富的匹配目标** — 消息文字、媒体说明、内联按钮文字/链接、发送者名称/ID、聊天名称/ID
- **白名单优先** — 排除规则始终优先于过滤规则，适合管理员、公告或信任联系人
- **一键重置规则** — 可一次清空全局与所有聊天的过滤规则，同时保留防撤回、编辑历史、消息标记、日志和功能设置
- **三种过滤动作** — 本地隐藏、本地折叠、调试标记（测试用）
- **完全宿主化设置** — 不提供独立应用界面；全局配置和聊天配置都在 Telegram 内完成，界面跟随宿主主题，修改后自动保存并立即生效
- **分类设置与规则列表** — 按功能分类导航，屏蔽规则和保留例外分别管理，支持逐条启停、搜索、编辑和批量添加
- **消息翻译** — 使用 Telegram 原生翻译服务，优先处理屏幕内消息；支持单条翻译、自动翻译及全局/群组/频道设置，原文在上、译文在下，目标语言可跟随界面或手动指定；翻译会向 Telegram 服务提交待翻译文本
- **配置迁移** — 设置页导出/导入 JSON，迁移规则、功能和当前账号策略；不包含登录凭据、缓存消息或媒体文件
- **解除内容限制** — 控制受限媒体的保存、复制和转发；副本发送由独立开关控制，默认关闭
- **受限内容发送副本（可选）** — 默认沿用 Telegram 原生转发并保留来源；开启后，受限文字和已完整下载的普通视频会作为副本上传，视频不保留原频道来源
- **消息标记与跳转** — 单击消息可标记位置，从右上角菜单一键跳回，每个聊天独立标记
- **浏览位置记忆** — 自动记录滚动位置，可一键跳转到上次浏览处
- **可选下载按钮常驻** — 默认关闭；开启后始终保留 Telegram 原生下载入口，点击、动画和进度仍由客户端处理
- **下载页全选** — Telegram 下载管理页面多选模式下支持一键全选
- **主动加载与防撤回防修改** — 后台和推送到达时主动加载消息，结合删除链路拦截和本地存储标记，尽量保留被撤回或修改的原始内容
- **多版本编辑历史** — 编辑历史按版本保存，并会从 Telegram 本地历史同步写入中补齐离线期间发生的编辑
- **编辑历史媒体查看** — 点击消息弹窗可查看编辑前内容，原始图片优先使用 Telegram 官方 PhotoViewer 并支持官方保存入口
- **持久化诊断日志** — 运行日志写入 app-specific 外部目录，避免依赖容易溢出的 logcat 缓冲区
- **双语界面** — 英文和简体中文，支持跟随系统

- **Local-only filtering** — all filtering happens on-device; no network requests, no data leaves your phone
- **Global + per-chat rules** — set broad filters globally, then override or exclude specific chats
- **Rich match targets** — message text, media captions, inline button labels/URLs, sender names/IDs, chat names/IDs
- **Whitelist wins first** — exclusion rules always override filter rules; use them for admins, notices, or trusted contacts
- **One-tap rule reset** — clear global and per-chat filter rules at once while preserving anti-recall, edit history, message marks, logs, and feature settings
- **Three filter actions** — hide locally, collapse locally, or debug-mark (for testing)
- **Fully host-native settings** — provides no standalone app UI; global and per-chat settings live inside Telegram, follow the host theme, and save and apply changes automatically
- **Categorized settings and rule lists** — navigate by feature and manage block rules and keep exceptions separately, with individual toggles, search, editing and batch input
- **Message translation** — uses Telegram's native translation service and prioritizes visible messages; supports single-message and automatic translation with global, group and channel settings, original text above the translation, and an interface-based or manually selected target language; text is submitted to Telegram's translation service
- **Settings migration** — export/import JSON from settings to transfer rules, features and current-account policies, excluding login credentials, cached messages and media files
- **Content restrictions** — controls saving, copying and forwarding of protected media; copy sending has a separate switch and is off by default
- **Optional protected-content copies** — Telegram's native forwarding keeps the source by default; when enabled, protected text and fully downloaded regular videos are uploaded as copies, with videos losing the original channel attribution
- **Mark & jump** — tap a message to mark its position, jump back anytime from the menu; marks are per-chat
- **Browse position memory** — automatically tracks scroll position, one-tap jump to last viewed message
- **Optional persistent download button** — off by default; when enabled, Telegram's native download entry stays available while clicks, animation, and progress remain client-controlled
- **Download page select all** — select all loaded download items at once in Telegram's download manager
- **Anti-recall & anti-edit** — proactively loads messages in the background and when push updates arrive, combining delete-path interception and local-storage marking to preserve recalled or edited content where possible
- **Multi-version edit history** — stores edit history by version and recovers edits that arrive through Telegram local history-sync writes while the device was offline
- **Edit-history media viewer** — open original pre-edit content from the message popup; original images prefer Telegram's official PhotoViewer and official save flow
- **Persistent diagnostics** — runtime logs are written to app-specific external storage instead of relying on overflow-prone logcat buffers
- **Bilingual UI** — English and Simplified Chinese, with system-follow option

编辑历史只能展示实际记录并缓存的内容；连续编辑图片时，中间图片版本可能未独立保存，不能保证完整恢复。新版各渠道的具体功能仍需按场景验证，适配声明不表示所有功能均已实测。

Edit history can only show content that was recorded and cached; intermediate images in consecutive edits may not be stored independently, so complete recovery is not guaranteed. Features require scenario-specific verification on each client build; adaptation does not mean every feature has been tested.

## 规则写法 How Rules Work

在 GramSieve 设置首页进入“屏蔽与保留”，分别管理屏蔽规则和保留例外。点击“添加规则”选择匹配内容、范围和方式，修改即时保存；已有规则可以单独启停、编辑或删除，多条内容可使用“批量添加”。保留例外始终优先。过滤后的隐藏、折叠或调试标记可在“过滤方式与规则管理”中选择。

Open “Block & keep rules” from GramSieve Settings to manage block rules and keep exceptions separately. Add a rule with its text, scope and matching method; edits save automatically. Existing rules can be enabled, edited or deleted individually, and “Add multiple” supports batch input. Keep exceptions take priority. Choose hide, collapse or debug mark under “Filtering action & rule management”.

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

- **Telegram 设置列表** → `GramSieve`（唯一全局配置入口）
- **聊天右上角三点菜单** → `聊天过滤规则` · `防撤回` · `主动加载` · `编辑历史` · `跳转到上次浏览` · `跳转到标记位置`；启用翻译后提供翻译及自动翻译入口，菜单项可在设置中分别隐藏
- **单击某条消息** → `屏蔽此消息` · `标记此消息` · `编辑历史`；启用翻译后可翻译有文字的消息
- **下载页面多选模式** → `全选` 按钮（一键选中所有已加载的下载项）

- **Telegram settings list** → `GramSieve` (the only global settings entry)
- **Chat top-right overflow menu** → `Chat filters` · `Anti-recall` · `Proactive loading` · `Edit history` · `Jump to last viewed` · `Jump to marked position`; translation controls appear when enabled, and individual menu entries can be hidden in settings
- **Click a message** → `Block this message` · `Mark this message` · `Edit history`; messages containing text can also be translated when translation is enabled
- **Download page action mode** → `Select All` button (select all loaded download items at once)

规则直接从 Telegram 内的寄生设置页保存到 LSPosed 远程配置，并同步持久化到模块进程；无需再打开模块应用确认。

Rules are saved from the host panel directly to LSPosed remote preferences and mirrored to the module process; opening a separate module app is no longer required.

## 持久化日志 Persistent Logs

调试防撤回、主动加载或媒体缓存时，优先读取持久化日志。公共 `/sdcard/GramSieve` 路径已放弃，日志写入 app-specific 外部目录：

When debugging anti-recall, proactive loading, or media caching, read persistent logs first. The public `/sdcard/GramSieve` path is no longer used; logs are written to app-specific external storage:

```
adb -s <device> shell tail -n 300 /sdcard/Android/data/org.telegram.messenger/files/GramSieve/gramsieve.log
adb -s <device> shell tail -n 300 /sdcard/Android/data/com.tianqianguai.gramsieve/files/GramSieve/gramsieve.log
```

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
