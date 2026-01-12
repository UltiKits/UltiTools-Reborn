# UltiTrade

[![UltiTools-API](https://img.shields.io/badge/UltiTools--API-6.x-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.13--1.21-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://www.java.com/)

UltiTrade 是一个功能完整的 Minecraft 玩家间交易系统插件，基于 UltiTools-API 框架开发。支持物品、金币、经验交易，具有完善的安全机制和丰富的用户体验功能。

## ✨ 功能特性

### 核心交易功能
- 🔄 **安全物品交易** - 双方确认机制，防止欺诈
- 💰 **金币交易** - 集成 Vault 经济系统，支持税收
- ⭐ **经验交易** - 支持经验值交换，可配置税率
- ⏱️ **超时机制** - 可配置的交易请求超时时间

### 用户体验
- 🎯 **BossBar 倒计时** - 直观显示交易请求剩余时间
- 🖱️ **可点击按钮** - 聊天消息中的 [接受]/[拒绝] 按钮
- 👆 **Shift+右键交易** - 快捷发起交易请求
- 🔊 **音效反馈** - 交易成功/失败/物品放置等音效
- ✨ **粒子特效** - 交易完成时的视觉效果
- 📋 **物品详情预览** - 悬浮显示附魔、耐久度等信息

### 安全与管理
- 🚫 **玩家黑名单** - 屏蔽不想交易的玩家
- 🔐 **交易开关** - 玩家可自主开关交易功能
- ⚠️ **大额交易确认** - 超过阈值自动弹出确认界面
- 📊 **交易日志** - 记录所有交易，支持自动清理
- 📈 **PlaceholderAPI** - 交易统计变量支持

## 📦 安装

1. 安装前置插件：
   - [UltiTools-API](https://github.com/UltiKits/UltiTools-Reborn) (必需)
   - [Vault](https://www.spigotmc.org/resources/vault.34315/) (金币交易)
   - [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) (可选)

2. 将 `UltiTrade.jar` 放入 `plugins/UltiTools/plugins/` 目录

3. 重启服务器或使用 `/ultitools reload`

## 🎮 命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/trade <玩家>` | `ultitrade.use` | 向玩家发起交易请求 |
| `/trade accept` | `ultitrade.use` | 接受交易请求 |
| `/trade deny` | `ultitrade.use` | 拒绝交易请求 |
| `/trade cancel` | `ultitrade.use` | 取消当前交易 |
| `/trade toggle` | `ultitrade.use` | 开启/关闭交易功能 |
| `/trade block <玩家>` | `ultitrade.use` | 屏蔽指定玩家 |
| `/trade unblock <玩家>` | `ultitrade.use` | 取消屏蔽玩家 |

**命令别名:** `/t`

## ⚙️ 配置

```yaml
# 基础设置
requestTimeout: 30           # 交易请求超时时间（秒）
maxDistance: 50              # 最大交易距离（-1 无限制）
allowCrossWorld: false       # 是否允许跨世界交易

# 交易功能
enableMoneyTrade: true       # 启用金币交易
enableExpTrade: true         # 启用经验交易
enableShiftClick: true       # 启用 Shift+右键发起交易

# 税收设置
tradeTax: 0.0                # 金币交易税率（0-1）
expTaxRate: 0.0              # 经验交易税率（0-1）

# 大额交易确认
confirmThreshold: 10000      # 确认阈值（金币或经验）

# 日志设置
enableTradeLog: true         # 启用交易日志
logRetentionDays: 30         # 日志保留天数
cleanupIntervalHours: 24     # 清理间隔（小时）

# 效果设置
enableSounds: true           # 启用音效
enableParticles: true        # 启用粒子效果
enableBossbar: true          # 启用 BossBar 倒计时
enableClickableButtons: true # 启用聊天可点击按钮
```

## 📊 PlaceholderAPI 变量

| 变量 | 描述 |
|------|------|
| `%ultitrade_total_trades%` | 玩家总交易次数 |
| `%ultitrade_total_money%` | 玩家总交易金额 |
| `%ultitrade_total_exp%` | 玩家总交易经验 |
| `%ultitrade_trade_enabled%` | 交易是否开启 |
| `%ultitrade_is_trading%` | 是否正在交易中 |
| `%ultitrade_last_trade_time%` | 上次交易时间 |
| `%ultitrade_blocked_count%` | 黑名单玩家数量 |

## 🎨 GUI 界面

交易界面采用 54 格大箱子布局：

```
[你的物品区 4x4] | [分隔线] | [对方物品区 4x4]
[金币] [状态] [经验] |   | [经验] [状态] [金币]
[取消] [...] [...] [确认] [...] [...] [...]
```

### 物品详情预览
悬浮在对方物品上可查看详细信息：
- 附魔列表及等级
- 耐久度百分比
- 物品标志
- 自定义模型数据
- 无法破坏标记

## 📝 更新日志

### v2.0.0
- 新增经验交易功能
- 新增玩家黑名单系统
- 新增交易开关功能
- 新增 BossBar 请求倒计时
- 新增可点击聊天按钮
- 新增 Shift+右键快捷交易
- 新增大额交易确认机制
- 新增交易日志系统
- 新增 PlaceholderAPI 集成
- 新增音效和粒子特效
- 新增物品详情悬浮预览
- 优化 GUI 界面布局
- 优化代码结构，使用 UltiTools-API 框架

### v1.0.0
- 初始版本
- 基础物品和金币交易功能

## 🔧 技术架构

- **框架**: UltiTools-API 6.x
- **注解驱动**: `@Service`, `@Autowired`, `@CmdMapping`, `@Table`
- **数据持久化**: DataOperator ORM
- **依赖注入**: UltiTools IoC 容器

### 主要类说明

| 类 | 说明 |
|---|---|
| `TradeService` | 核心交易逻辑服务 |
| `TradeLogService` | 日志记录和玩家设置管理 |
| `TradeGUI` | 交易界面实现 |
| `TradeConfirmPage` | 大额交易确认页面 |
| `TradeListener` | 事件监听处理 |
| `TradeCommand` | 命令执行器 |
| `TradePlaceholderExpansion` | PlaceholderAPI 扩展 |

### 数据实体

| 实体 | 用途 |
|---|---|
| `TradeLogData` | 交易日志记录 |
| `PlayerTradeSettings` | 玩家交易设置和统计 |
| `SerializedItemStack` | 物品序列化 (JSON) |
| `TradeSession` | 活跃交易会话 |

## 📜 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 🤝 贡献

欢迎提交 Pull Request 或创建 Issue！

## 🔗 相关链接

- [UltiTools-API 文档](https://doc.dev.ultikits.com/)
- [UltiKits 官网](https://www.ultikits.com/)
- [SpigotMC](https://www.spigotmc.org/)
