# Telegram LSPosed 过滤模块开发指南

这是一份给执行型 agent 的开发说明。目标是实现一个 **LSPosed 模块**，用于在 Android Telegram 客户端中 **本地隐藏/折叠普通垃圾广告消息**，而不是只移除官方 Sponsored Messages。

## 1. 任务目标

开发一个 Android + LSPosed 模块，具备以下能力：

- 作用于 **官方 Telegram Android**，包名优先 `org.telegram.messenger`
- 本地识别并隐藏“看起来是普通消息”的垃圾广告
- 支持 **关键词匹配** 与 **Java Regex 匹配**
- 支持过滤：
  - 文本正文
  - caption
  - inline buttons
  - 指定发送者
  - 指定聊天
- 支持 **全局规则** 与 **按聊天规则**
- 支持 **排除列表**
- 默认行为是 **仅本地隐藏**，不删除服务器消息，不撤回，不自动拉黑

## 2. 非目标

首版不要做这些：

- 不做网络层 MITM
- 不改 Telegram 服务端行为
- 不自动举报/拉黑/删除消息
- 不先做多客户端兼容
- 不先做复杂的 NLP/AI 分类器
- 不先改数据库持久化内容

首版重点是：**稳定、可控、可回退的本地视图过滤**。

## 3. 设计原则

- 从 **UI 渲染层** 切入，而不是数据库层或 update 分发层
- 先保证“隐藏正确”和“不崩溃”，再追求“像原生一样完全无痕”
- 所有规则匹配都在本地完成，不上传任何消息内容
- 对 Telegram 升级敏感的 hook 点，要做多层 fallback
- 允许“首版是软隐藏”，后续再迭代成“列表级移除”

## 4. 技术路线

### 4.1 首版推荐架构

拆成四层：

1. **Hook 层**
   - 负责注入 Telegram 进程
   - 获取 `MessageObject`、消息文本、按钮、聊天信息、发送者信息

2. **归一化层**
   - 将 Telegram 消息转成统一可匹配结构
   - 输出 `NormalizedMessage`

3. **规则引擎**
   - 执行关键词、regex、发送者、聊天、按钮等匹配
   - 输出 `MatchDecision`

4. **呈现层**
   - 根据匹配结果决定：
     - 隐藏
     - 折叠
     - 透明显示
     - 调试高亮

### 4.2 为什么先选 UI 层

因为数据库层和 update 层更“干净”，但风险更高：

- 容易影响未读数、回复链、跳转、搜索、通知
- Telegram 升级后更容易崩
- 一旦误删持久化或中断消息列表，回退成本高

UI 层方案即使失败，通常只是“不隐藏”或“显示异常”，不会破坏会话数据。

## 5. 建议功能切分

按阶段实现：

### Phase 1: MVP

- 支持官方 Telegram `org.telegram.messenger`
- 全局启用/关闭
- 全局关键词匹配
- 全局 Java Regex 匹配
- 匹配正文与 caption
- 命中后在聊天界面隐藏该消息气泡
- 提供调试日志

### Phase 2

- 匹配 inline buttons
- 按聊天规则
- 排除列表
- 按发送者过滤
- 命中后支持“折叠而非完全隐藏”

### Phase 3

- 列表级过滤，尽量不留空白占位
- 频道/群组差异化规则
- 导入/导出规则
- UI 配置页
- 兼容 NekoX / Cherrygram / exteraGram 等分支客户端

## 6. 当前已确认的 Telegram 落点

基于官方 Telegram Android 源码，以下类/字段/方法适合作为观察点：

- `org.telegram.messenger.MessageObject`
  - `messageText`
  - `caption`
  - `sponsoredId`
- `org.telegram.ui.Cells.ChatMessageCell`
  - `setMessageObject(...)`
  - `setMessageObjectInternal(...)`
- `org.telegram.messenger.MessagesController`
  - `processUpdates(...)`
  - 处理 `TL_updateNewMessage`
  - 处理 `TL_updateNewChannelMessage`
- `org.telegram.messenger.MessagesStorage`
  - `putMessages(...)`

### 结论

首版优先 hook：

- `ChatMessageCell.setMessageObject(...)`
- 如有必要，再观察 `setMessageObjectInternal(...)`

不要在首版直接改：

- `MessagesStorage.putMessages(...)`
- `MessagesController.processUpdates(...)`

原因：首版先做“渲染抑制”，不碰消息持久化和分发逻辑。

## 7. 匹配对象模型

为规则引擎准备统一结构：

```java
final class NormalizedMessage {
    long dialogId;
    long senderId;
    boolean isChannel;
    boolean isGroup;
    boolean isPrivate;
    String text;
    String caption;
    List<String> buttons;
    boolean hasInlineButtons;
    boolean hasUrl;
    boolean hasMention;
    boolean hasPhone;
    boolean hasCryptoLikePattern;
}
```

### 归一化建议

- `text`: 从 `MessageObject.messageText` 提取
- `caption`: 从 `MessageObject.caption` 提取
- `buttons`: 从 `messageOwner.reply_markup` 遍历提取按钮文本和 URL
- 文本做统一处理：
  - trim
  - lowercase
  - 合并连续空白
  - 可选去零宽字符
  - 可选全角半角归一

### 额外建议

构造一个 `searchBlob`，便于统一 regex：

```text
<text> ...正文...
<caption> ...caption...
<button> ...按钮1...
<button> ...按钮2...
```

这样一条 regex 就能同时匹配按钮和正文。

## 8. 规则模型

先用简单、稳定、便于持久化的模型：

```java
enum RuleType {
    KEYWORD,
    REGEX,
    SENDER_ID,
    CHAT_ID
}

enum ActionType {
    HIDE,
    COLLAPSE,
    DIM,
    DEBUG_MARK
}
```

每条规则包含：

- `id`
- `enabled`
- `type`
- `pattern`
- `isRegex`
- `caseSensitive`
- `scope` (`GLOBAL` / `CHAT`)
- `targetChatId`
- `action`
- `excludeChats`
- `excludeSenders`

## 9. 匹配策略

### 9.1 建议优先级

1. exclusions
2. sender/chat hard deny
3. regex
4. keyword

### 9.2 首版默认规则建议

先内置少量可关闭的默认模式：

```text
https?://
t\.me/
telegram\.me/
加微|加v|兼职|返利|空投|稳赚|日结
```

不要把默认规则写得太激进，避免误伤。

### 9.3 性能要求

- Regex 在规则加载时预编译，运行时不要重复编译
- 对同一 `messageId` 做短期缓存
- 只在必要 hook 点匹配一次

## 10. 隐藏策略

首版实现顺序：

### 策略 A: 软隐藏

命中后对当前消息气泡：

- `setVisibility(View.GONE)` 或等效处理
- 高度设为 0
- 清空 click / long-click

如果直接 `GONE` 引发布局问题，则退回：

### 策略 B: 折叠隐藏

- 替换显示文本为固定占位，例如 `Filtered message`
- 压缩 padding / 高度
- 隐藏 media / buttons

### 策略 C: 调试模式

- 不隐藏，只加明显背景色或前缀，验证规则是否命中正确

首版必须支持调试模式，否则排查会很痛苦。

## 11. LSPosed 模块结构

使用 **Modern Xposed API**。

项目至少包含：

- `src/main/resources/META-INF/xposed/java_init.list`
- `src/main/resources/META-INF/xposed/scope.list`
- `src/main/resources/META-INF/xposed/module.prop`
- 一个实现 `io.github.libxposed.api.XposedModule` 的入口类

### `java_init.list`

写模块入口类全名，一行一个。

### `scope.list`

首版只写：

```text
org.telegram.messenger
```

### `module.prop`

至少包含：

```properties
minApiVersion=...
targetApiVersion=...
staticScope=true
```

具体 API 版本按当前 libxposed/LSPosed 文档对齐，不要拍脑袋写死旧值。

## 12. 配置存储

优先级：

1. 使用 LSPosed 现代 API 可用的远程配置能力
2. 如果实现复杂，首版退回普通模块 App 内部存储 + XSharedPreferences 兼容读法

要求：

- Telegram 进程内读取配置必须安全
- 规则变更后尽量支持热更新
- 至少支持“重新进入聊天后生效”

## 13. 建议代码结构

```text
app/
  src/main/java/.../ModuleEntry.java
  src/main/java/.../TelegramHookEntry.java
  src/main/java/.../hook/ChatMessageCellHooks.java
  src/main/java/.../match/NormalizedMessage.java
  src/main/java/.../match/Rule.java
  src/main/java/.../match/RuleEngine.java
  src/main/java/.../config/ConfigRepository.java
  src/main/java/.../config/RuleSerializer.java
  src/main/java/.../ui/MainActivity.java
  src/main/resources/META-INF/xposed/java_init.list
  src/main/resources/META-INF/xposed/scope.list
  src/main/resources/META-INF/xposed/module.prop
```

## 14. Hook 实现要求

### 14.1 入口要求

- 只在目标包名命中时初始化 hook
- 所有反射和 hook 注册都要带错误保护
- 任一 hook 失败不能让 Telegram 直接崩

### 14.2 Telegram 版本兼容

必须做：

- 方法名存在性检查
- 参数签名检查
- 多候选 hook 点 fallback
- 日志打印 Telegram 版本号、命中的 hook 点、失败原因

### 14.3 不要做的事

- 不要直接假设类永远不变
- 不要把所有逻辑塞进 hook 回调
- 不要在 UI 线程里做大规模 regex 编译

## 15. 调试和日志

至少要有这些日志：

- 模块是否成功注入 Telegram
- Telegram 版本号
- 目标类和方法是否找到
- 每条消息是否进入规则引擎
- 命中的是哪条规则
- 最终执行的是哪种 action

日志要可关闭。

## 16. 测试清单

至少验证这些场景：

1. 普通私聊消息不误杀
2. 群广告文本可命中并隐藏
3. 带 inline button 的广告可命中
4. caption 广告可命中
5. 排除聊天后不再命中
6. 关闭模块后 Telegram 正常显示
7. Telegram 升级后 hook 失败时不崩溃

## 17. 验收标准

满足以下条件才算首版完成：

- 模块能在 LSPosed 中正常加载
- Telegram 官方客户端可稳定启动
- 至少支持 3 条自定义规则
- 至少支持 1 条 regex 规则
- 能稳定隐藏命中的普通文本广告消息
- 不影响消息发送、接收、回复、滚动
- 关闭模块后所有行为恢复正常

## 18. 实施顺序

请 agent 按这个顺序推进：

1. 创建最小可运行 LSPosed 模块骨架
2. 完成 Telegram 包名命中与日志注入
3. 在 `ChatMessageCell.setMessageObject(...)` 上拿到 `MessageObject`
4. 读取 `messageText` / `caption`
5. 实现 `NormalizedMessage`
6. 实现最小规则引擎
7. 接入调试模式
8. 接入隐藏动作
9. 增加配置页与规则存储
10. 补测试与兼容性日志

## 19. 对 agent 的明确要求

- 直接实现，不要先停下来问“是否继续”
- 先做 MVP，再补高级功能
- 先 hook UI 层，不要首版改数据库层
- 每完成一层就做一次真机/模拟器验证
- 保持最小依赖，不额外引入重型库
- 所有规则与动作都要可回退
- 如果 Telegram 版本导致 hook 点变动，优先加 fallback，不要硬改成只支持单一版本

## 20. 交付物

最终应交付：

- 可编译的 Android 项目
- LSPosed 模块 APK
- 简短 README
- 已知兼容 Telegram 版本列表
- 示例规则集
- 调试开关说明

## 21. 建议给 agent 的起始提示词

可直接把下面这段发给 agent：

```text
请实现一个 Android LSPosed 模块，用于在官方 Telegram Android 客户端 org.telegram.messenger 中本地隐藏普通垃圾广告消息。首版仅支持官方 Telegram，优先在 UI 渲染层实现，不要先改数据库层或消息分发层。使用 Modern Xposed API，建立最小可运行模块骨架，并优先 hook ChatMessageCell.setMessageObject(...) 以获取 MessageObject。提取 messageText、caption 和 inline buttons，构建统一 NormalizedMessage 结构，实现支持关键词和 Java regex 的规则引擎，并在命中后执行隐藏或调试标记动作。需要提供最小配置页、规则存储、日志开关和 fallback hook 策略。目标是稳定、不崩溃、可回退，不删除服务器消息，不自动拉黑，不引入重型依赖。
```

## 22. 参考资料

- LSPosed Modern API 开发说明：
  https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API
- Telegram Android 官方源码：
  https://github.com/DrKLO/Telegram
- AyuGram Filters 文档：
  https://docs.ayugram.one/android/filters/

