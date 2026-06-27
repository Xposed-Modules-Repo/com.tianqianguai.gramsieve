# 主动加载与防撤回防修改功能设计

## [S1] 问题概述

Telegram 在后台或消息列表时不会主动加载消息内容，导致防撤回/防修改插件失效。本功能通过主动触发消息加载，确保所有聊天的消息内容在后台也能被实时缓存，从而在消息被撤回或修改时保留原始内容。

## [S2] 解决方案概述

### 核心组件

1. **MessageCache** - 消息缓存管理器，存储原始消息内容
2. **BackgroundMessageLoader** - 后台消息加载器，主动触发消息加载
3. **RecallDetector** - 撤回/修改检测器，监听消息更新事件
4. **AntiRecallConfig** - 配置管理，支持每聊天独立配置

### 工作流程

1. 用户在聊天菜单中启用"主动加载与防撤回防修改"功能
2. BackgroundMessageLoader 主动加载该聊天的消息
3. MessageCache 缓存消息的原始内容
4. RecallDetector 监听 MessagesController 的更新回调
5. 当检测到消息被撤回/修改时，在 UI 中标记并保留原始内容

## [S3] 数据流和存储

### 消息缓存结构

```java
class CachedMessage {
    long dialogId;
    long messageId;
    long senderId;
    String text;
    String caption;
    long timestamp;
    boolean isRecalled;
    boolean isEdited;
    String editedText; // 编辑后的新内容
}
```

### 存储方案

- 使用 SharedPreferences 存储配置
- 使用内存缓存 (LRU Cache) 存储消息内容
- 对于大量消息，使用 SQLite 数据库持久化存储
- **永不过期** - 消息内容永久保存

### 数据流

1. 新消息到达 → 缓存到 MessageCache
2. 消息更新事件 → 检查是否为撤回/修改
3. 如果是撤回 → 标记 isRecalled=true
4. 如果是修改 → 保存原内容，标记 isEdited=true
5. UI 渲染时 → 根据标记显示原始内容

## [S4] UI 和用户交互

### 功能名称

"主动加载与防撤回防修改"

### 配置入口

1. **聊天菜单** - 在聊天右上角菜单中添加"主动加载与防撤回防修改"开关
2. **全局设置** - 在 GramSieve 设置中添加全局开关

### 消息标记显示

- **撤回的消息** - 显示"[此消息已被撤回]"并保留原始内容
- **修改的消息** - 显示原始内容，并标注"已编辑"

### 用户操作

- 点击被撤回/修改的消息可查看详细信息
- 长按可复制原始内容

## [S5] 技术实现细节

### Hook 点

1. **MessagesController.processUpdateArray** - 监听消息更新数组
2. **MessagesController.processUpdate** - 监听单个消息更新
3. **MessagesController.deleteMessages** - 监听消息删除
4. **MessagesController.editMessage** - 监听消息编辑

### 主动加载机制

- 使用 Telegram 的 **MessagesController.loadMessages** 方法
- 对于每个启用防撤回的聊天，定期触发加载
- 加载频率可配置（默认 30 秒）

### 消息缓存策略

- 使用 LRU Cache，最大缓存 1000 条消息
- 对于超过缓存的消息，使用 SQLite 持久化存储
- **永不过期** - 消息内容永久保存

## [S6] 错误处理和边缘情况

### 错误处理

1. **Hook 失败** - 记录日志，继续运行其他功能
2. **缓存满** - 使用 LRU 策略淘汰旧数据
3. **数据库错误** - 降级到内存缓存

### 边缘情况

1. **消息已删除** - 在缓存中保留标记
2. **消息编辑多次** - 保存所有版本
3. **大量消息** - 使用分页加载

### 性能考虑

- 后台加载使用低优先级线程
- 缓存查询使用内存缓存
- 数据库操作异步执行

## [S7] 文件结构

### 新增文件

```
app/src/main/java/com/tianqianguai/gramsieve/
├── module/
│   ├── AntiRecallModule.java          # 防撤回主模块
│   ├── MessageCache.java              # 消息缓存管理器
│   ├── BackgroundMessageLoader.java   # 后台消息加载器
│   ├── RecallDetector.java            # 撤回/修改检测器
│   └── AntiRecallConfig.java          # 配置管理
├── config/
│   └── AntiRecallConfigStore.java     # 配置存储
└── ui/
    └── AntiRecallSettingsActivity.java # 设置界面
```

### 数据库表结构

```sql
CREATE TABLE cached_messages (
    dialog_id INTEGER,
    message_id INTEGER,
    sender_id INTEGER,
    text TEXT,
    caption TEXT,
    timestamp INTEGER,
    is_recalled INTEGER DEFAULT 0,
    is_edited INTEGER DEFAULT 0,
    edited_text TEXT,
    PRIMARY KEY (dialog_id, message_id)
);
```

## [S8] 配置项

### 全局配置

- `anti_recall_enabled` - 全局开关（默认 false）
- `load_interval_seconds` - 加载间隔（默认 30 秒）
- `max_cache_size` - 最大缓存数量（默认 1000）

### 每聊天配置

- `anti_recall_chat_{dialogId}` - 每聊天开关（默认 false）

## [S9] 测试策略

### 单元测试

1. MessageCache 缓存逻辑
2. RecallDetector 检测逻辑
3. AntiRecallConfig 配置管理

### 集成测试

1. Hook 安装和消息监听
2. 后台加载机制
3. UI 显示和交互

### 手动测试

1. 发送消息后撤回，验证是否保留原始内容
2. 发送消息后编辑，验证是否显示编辑历史
3. 在后台运行时，验证消息是否被主动加载
4. 在不同聊天中启用/禁用功能，验证配置是否生效

## [S10] 实施计划

### 阶段 1：基础设施

1. 创建数据库表结构
2. 实现 MessageCache 和 AntiRecallConfig
3. 添加配置存储

### 阶段 2：核心功能

1. 实现 BackgroundMessageLoader
2. 实现 RecallDetector
3. 安装 Hook 点

### 阶段 3：UI 集成

1. 添加聊天菜单项
2. 实现消息标记显示
3. 添加全局设置

### 阶段 4：测试和优化

1. 编写单元测试
2. 进行集成测试
3. 性能优化和错误处理
