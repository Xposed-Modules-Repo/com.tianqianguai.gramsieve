# GramSieve

Telegram 消息过滤与浏览位置跳转等 LSPosed 模块。

An LSPosed module for Telegram message filtering, browsing position redirection, and more.

## 功能 Features

- **仅本地过滤** — 所有过滤在设备上完成，无网络请求，数据不离开手机
- **全局 + 单聊规则** — 全局设置宽泛规则，再针对特定聊天覆盖或排除
- **丰富的匹配目标** — 消息文字、媒体说明、内联按钮文字/链接、发送者名称/ID、聊天名称/ID
- **白名单优先** — 排除规则始终优先于过滤规则，适合管理员、公告或信任联系人
- **三种过滤动作** — 本地隐藏、本地折叠、调试标记（测试用）
- **消息标记与跳转** — 单击消息可标记位置，从右上角菜单一键跳回，每个聊天独立标记
- **浏览位置记忆** — 自动记录滚动位置，可一键跳转到上次浏览处
- **下载页全选** — Telegram 下载管理页面多选模式下支持一键全选
- **主动加载与防撤回防修改** — 后台和推送到达时主动加载消息，保留被撤回或修改的原始内容
- **编辑历史媒体查看** — 点击消息弹窗可查看编辑前内容，原始图片优先使用 Telegram 官方 PhotoViewer 并支持官方保存入口
- **持久化诊断日志** — 运行日志写入 app-specific 外部目录，避免依赖容易溢出的 logcat 缓冲区
- **双语界面** — 英文和简体中文，支持跟随系统

- **Local-only filtering** — all filtering happens on-device; no network requests, no data leaves your phone
- **Global + per-chat rules** — set broad filters globally, then override or exclude specific chats
- **Rich match targets** — message text, media captions, inline button labels/URLs, sender names/IDs, chat names/IDs
- **Whitelist wins first** — exclusion rules always override filter rules; use them for admins, notices, or trusted contacts
- **Three filter actions** — hide locally, collapse locally, or debug-mark (for testing)
- **Mark & jump** — tap a message to mark its position, jump back anytime from the menu; marks are per-chat
- **Browse position memory** — automatically tracks scroll position, one-tap jump to last viewed message
- **Download page select all** — select all loaded download items at once in Telegram's download manager
- **Anti-recall & anti-edit** — proactively loads messages in the background and when push updates arrive, preserving original content when recalled or edited
- **Edit-history media viewer** — open original pre-edit content from the message popup; original images prefer Telegram's official PhotoViewer and official save flow
- **Persistent diagnostics** — runtime logs are written to app-specific external storage instead of relying on overflow-prone logcat buffers
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

- **Telegram 设置菜单** → `GramSieve 过滤规则`
- **单击某条消息** → `屏蔽此消息` · `标记此消息` · `编辑历史`
- **右上角三点菜单** → `主动加载` · `跳转到上次浏览` · `跳转到标记位置`
- **下载页面多选模式** → `全选` 按钮（一键选中所有已加载的下载项）

- **Telegram settings menu** → `GramSieve filters`
- **Click a message** → `Block this message` · `Mark this message` · `Edit history`
- **Top-right overflow menu** → `Proactive loading` · `Jump to last viewed` · `Jump to marked position`
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

## 示例规则 Sample Rules

- [sample-global-rules.txt](examples/sample-global-rules.txt)
- [sample-chat-rules.txt](examples/sample-chat-rules.txt)
- [sample-config.json](examples/sample-config.json)
