# ADB 功能验证

所有命令都走 Telegram 进程内已有 CLI，不读取模块 App 的另一份配置。必须指定设备、包名及 `--receiver-registered-only`。

```powershell
adb -s 192.168.6.90:5555 shell am broadcast -a com.tianqianguai.gramsieve.action.CLI -p org.telegram.messenger --receiver-registered-only --es command feature.inspect --es name hide_phone_number
```

成功响应必须包含 `result=-1` 和 `data` 中的 `ok:true`。`result=0` 且没有 data 不是功能关闭，可能是进程未注册 CLI、设备锁屏或应用冻结；先正常打开 Telegram。系统 `dumpsys activity broadcasts` 可以显示 `Greezer Denial` 等拒绝原因。

## 接口

将上述命令中的 `--es command` 及附加参数替换为：

| command | 参数 | 用途 |
|---|---|---|
| `feature.inspect` | `--es name <feature-key>`；省略或 `all` 返回全部 | 配置、实际方法、Hook 注册状态、字段类型兼容性及调用计数 |
| `feature.trace.start` | 无 | 清零并开始 60 秒的增强 Hook 调用计数 |
| `feature.trace.stop` | 无 | 停止计数并返回方法签名与调用次数 |
| `ui.enhancements.state` | 无 | 当前首页、资料页、聊天页目标字段及可见性、尺寸、布局高度、坐标、消息 ID 时间标签 |
| `ui.config.controls` | 可选 `--es name <control-key>` | 设置页真实开关值和控件状态；须先打开设置页 |
| `ui.config.control.set` | `--es name <control-key> --es value true/false` | 通过真实 Switch 监听器修改设置，用于验证即时保存，不点击“保存” |

设置页控件 key 包括现有增强功能 key，以及 `filter_enabled`、`debug_logging`、`exclude_global`、`chat_anti_recall`、`edit_history_enabled`、`fallback.<MODULE_NAME>`。命令不支持任意字段写入或任意反射调用。

`feature.inspect` 的 `status` 区分 `class_missing`、`method_missing`、`field_missing_or_incompatible`、`not_registered`、`registered_for_other_handler`、`registered_behavior_unverified`、`static_flags_only`、`external_owner` 和 `unavailable_in_build`。检查注册时使用本代实际成功挂载的方法集合，`registeredBy` 和 `handlerMatches` 区分是否由该功能的处理器挂载，不以方法存在或开关开启代替挂载成功。由主 Hook 安装器拥有的消息重载、下载入口等能力返回 `external_owner`，继续使用已有专用命令验证。

## 验证顺序

1. `config.get` 保存基线。检查 `feature.inspect` 的目标和字段。
2. `feature.trace.start`，执行待验证的正常操作，再 `feature.trace.stop`。计数是 Hook 调用次数，多重 sendRequest 重载可能计数多次，不能当作唯一网络请求数或功能成功次数。
3. `ui.enhancements.state` 对比开启/关闭的可见性、尺寸及字段状态。数组、null、字段不存在和 false 值分别报告。默认不导出消息正文、手机号或资料名；消息时间标签用于核对显示 ID。
4. 验证设置页时，使用 `ui.config.controls` → `ui.config.control.set` → `feature.get/config.get`，确认 UI 值实际持久化。
5. 逐项恢复本轮改动的设置，比较完整配置（忽略更新时间），不要用旧快照覆盖用户同时产生的新设置。

视图的 `globallyVisible` 是 Android `getGlobalVisibleRect()` 的原始返回值，仅作几何参考。实际可见性需要同时检查 `visibility`、`shown`、`attached` 和 `alpha`；隐藏视图可能仍保留旧测量尺寸和坐标。

界面状态还包含 `input`（语音/即时相机模式）、`stickers`（原生高级标签索引）、`messageMenuItems`、`storyMenuItems` 和 `mediaAction`。语音保存由实际已注册的消息菜单桥接处理，`feature.inspect` 返回该桥接方法及 `gramsieve.save_voice_messages` 动作标记。保存动作返回 `saving`、`saved`（原生回调返回 Uri）或 `download_required` 等状态；Story 使用原生保存入口，`native_save_invoked` 不等同于文件已保存。`premiumTabNum < 0` 表示当前没有独立高级标签，不应把其他贴纸包当作高级页隐藏。

计数默认关闭，60 秒到期停止累加，热重载后清零。接口不把目标存在、Hook 被调用或 UI 勾选自动判为端到端通过。发送消息、上传、已读状态等仍需明确测试对象和对端证据；像素布局或动画质量仍可按需截图。
