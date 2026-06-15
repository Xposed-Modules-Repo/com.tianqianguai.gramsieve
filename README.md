# GramSieve

Telegram 消息过滤与浏览位置跳转等 LSPosed 模块。

An LSPosed module for Telegram message filtering, browsing position redirection, and more.

## 功能 Features

- **仅本地过滤** — 所有过滤在设备上完成，无网络请求，数据不离开手机
- **全局 + 单聊规则** — 全局设置宽泛规则，再针对特定聊天覆盖或排除
- **丰富的匹配目标** — 消息文字、媒体说明、内联按钮文字/链接、发送者名称/ID、聊天名称/ID
- **白名单优先** — 排除规则始终优先于过滤规则，适合管理员、公告或信任联系人
- **三种过滤动作** — 本地隐藏、本地折叠、调试标记（测试用）
- **双语界面** — 英文和简体中文，支持跟随系统

- **Local-only filtering** — all filtering happens on-device; no network requests, no data leaves your phone
- **Global + per-chat rules** — set broad filters globally, then override or exclude specific chats
- **Rich match targets** — message text, media captions, inline button labels/URLs, sender names/IDs, chat names/IDs
- **Whitelist wins first** — exclusion rules always override filter rules; use them for admins, notices, or trusted contacts
- **Three filter actions** — hide locally, collapse locally, or debug-mark (for testing)
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
- **长按某条消息** → `屏蔽此消息`

- **Telegram settings menu** → `GramSieve filters`
- **Long-press a message** → `Block this message`

规则存储在模块应用内，通过 LSPosed service bridge 同步给 Telegram 读取。

Rules are stored in the module app and synced to the LSPosed service bridge so Telegram can read them.

## 安装 Install

1. 从 [Releases](https://github.com/Xposed-Modules-Repo/com.tianqianguai.gramsieve/releases) 下载最新签名 APK
2. 在设备上安装 APK
3. 在 LSPosed Manager 中启用 GramSieve
4. 作用域限定为 `org.telegram.messenger`
5. 强制停止 Telegram 后重新打开
6. 从 Telegram 设置中打开 `GramSieve 过滤规则`

1. Download the latest signed APK from [Releases](https://github.com/Xposed-Modules-Repo/com.tianqianguai.gramsieve/releases)
2. Install the APK on your device
3. Enable GramSieve in LSPosed Manager
4. Keep the scope limited to `org.telegram.messenger`
5. Force-stop Telegram, then reopen it
6. Open `GramSieve filters` from Telegram settings

## 系统要求 Requirements

- Android 13+ (API 33)
- LSPosed (Zygisk 或/or Riru)
- 推荐作用域/Recommended scope: `org.telegram.messenger`

## 示例规则 Sample Rules

- [sample-global-rules.txt](examples/sample-global-rules.txt)
- [sample-chat-rules.txt](examples/sample-chat-rules.txt)
- [sample-config.json](examples/sample-config.json)

## 局限 Limits

- 过滤仅在本地视觉层面生效 — GramSieve 不会删除消息、举报用户、拉黑用户或修改 Telegram 存储。
- Telegram UI 内部结构会随版本变化，新版 Telegram 可能需要更新模块才能适配。

- Filtering is visual and local only — GramSieve does not delete messages, report users, block users, or modify Telegram storage.
- Telegram UI internals change over time; new Telegram releases may need hook updates before every filter entry point works again.
