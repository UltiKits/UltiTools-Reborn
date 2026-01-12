# UltiRemoteBag

[![UltiTools Module](https://img.shields.io/badge/UltiTools-Module-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.13--1.21-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://www.oracle.com/java/)

**UltiRemoteBag** 是 UltiTools-API 框架的一个插件模块，为 Minecraft 服务器提供虚拟云存储（远程背包）功能。玩家可以随时随地访问自己的远程背包，安全存储物品。

## ✨ 功能特性

### 核心功能
- 🎒 **多页背包系统** - 每个玩家可拥有多个背包页，每页 45 个槽位
- 💾 **自动保存** - 支持定时自动保存和关闭时保存
- 🔐 **背包锁定机制** - 防止并发访问冲突，支持编辑/只读模式
- 📊 **物品统计** - 在 GUI 中显示物品数量和槽位占用

### 经济集成
- 💰 **Vault 经济支持** - 可配置的背包购买价格
- 📈 **价格递增** - 可选的价格递增公式：`basePrice × (1 + rate)^(n-1)`
- 💵 **余额显示** - 购买界面显示当前余额和价格

### 管理员功能
- 👁️ **查看玩家背包** - 管理员可查看任意玩家的背包
- ➕ **创建背包** - 为玩家创建新的背包页
- 🗑️ **删除背包** - 删除指定玩家的背包页
- 🧹 **清空背包** - 清空背包内容但保留页面
- 📋 **列出背包** - 查看玩家所有背包的概览

### 访问控制
- 🔒 **所有者优先** - 当所有者使用背包时，管理员自动进入只读模式
- 🔄 **模式切换** - 只读模式下可刷新检查是否可升级为编辑模式
- ⏱️ **锁超时** - 可配置的锁超时时间，防止死锁

### 用户体验
- 🔊 **音效反馈** - 可配置的打开、关闭、购买、错误音效
- 🎨 **现代化 GUI** - 美观的分页界面，清晰的状态指示
- 🌍 **多语言支持** - 内置中文和英文支持

## 📦 安装

### 依赖项
- **UltiTools-API 6.2.0+** - 核心框架
- **Vault** (可选) - 经济功能支持

### 安装步骤
1. 确保已安装 UltiTools-API
2. 将 `UltiRemoteBag.jar` 放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或执行 `/ultitools reload`
4. 编辑配置文件 `plugins/UltiTools/UltiRemoteBag/config.yml`

## ⚙️ 配置说明

### config.yml

```yaml
# 基础配置
max-pages: 5                    # 最大背包页数
default-pages: 1                # 默认背包页数
rows-per-page: 5                # 每页行数 (1-6)
auto-save-interval: 300         # 自动保存间隔（秒），0 为禁用
save-on-close: true             # 关闭时保存

# 权限配置
permission-based-pages: false   # 是否基于权限决定页数
permission-prefix: "ultibag.pages."  # 权限前缀

# 经济配置
economy:
  enabled: true                 # 是否启用经济系统
  base-price: 1000              # 基础价格
  price-increase:
    enabled: true               # 是否启用价格递增
    rate: 0.5                   # 递增比率 (50%)

# 音效配置
sounds:
  enabled: true                 # 是否启用音效
  open: "BLOCK_CHEST_OPEN"      # 打开音效
  close: "BLOCK_CHEST_CLOSE"    # 关闭音效
  purchase: "ENTITY_PLAYER_LEVELUP"  # 购买音效
  error: "ENTITY_VILLAGER_NO"   # 错误音效
  volume: 1.0                   # 音量
  pitch: 1.0                    # 音调

# 锁定配置
lock:
  timeout: 300                  # 锁超时时间（秒）
  notify-readonly-viewers: true # 通知只读查看者
```

## 📜 命令

### 玩家命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/bag` | 打开背包主页 | `ultibag.use` |
| `/bag <页码>` | 打开指定页背包 | `ultibag.use` |
| `/bag save` | 手动保存背包 | `ultibag.use` |

### 管理员命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/bag see <玩家> [页码]` | 查看玩家背包 | `ultibag.admin.see` |
| `/bag create <玩家>` | 为玩家创建背包 | `ultibag.admin.create` |
| `/bag delete <玩家> <页码>` | 删除玩家背包页 | `ultibag.admin.delete` |
| `/bag clear <玩家> <页码>` | 清空玩家背包页 | `ultibag.admin.clear` |
| `/bag list <玩家>` | 列出玩家所有背包 | `ultibag.admin.list` |

### 命令别名
- `/bag`, `/remotebag`, `/rb`, `/yunbag`

## 🔑 权限节点

### 玩家权限

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultibag.use` | 使用远程背包 | true |
| `ultibag.pages.1` | 拥有 1 页背包 | true |
| `ultibag.pages.2` | 拥有 2 页背包 | false |
| `ultibag.pages.3` | 拥有 3 页背包 | false |
| `ultibag.pages.N` | 拥有 N 页背包 | false |

### 管理员权限

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultibag.admin.*` | 所有管理员权限 | op |
| `ultibag.admin.see` | 查看玩家背包 | op |
| `ultibag.admin.create` | 创建玩家背包 | op |
| `ultibag.admin.delete` | 删除玩家背包 | op |
| `ultibag.admin.clear` | 清空玩家背包 | op |
| `ultibag.admin.list` | 列出玩家背包 | op |

## 🏗️ 架构设计

### 项目结构

```
com.ultikits.plugins.remotebag/
├── UltiRemoteBag.java          # 插件主类
├── commands/
│   └── BagCommand.java         # 命令执行器
├── config/
│   └── RemoteBagConfig.java    # 配置类
├── entity/
│   ├── RemoteBagData.java      # 数据实体
│   ├── BagLockInfo.java        # 锁信息
│   └── BagOpenResult.java      # 打开结果
├── enums/
│   ├── LockType.java           # 锁类型 (OWNER/ADMIN)
│   └── AccessMode.java         # 访问模式 (EDIT/READ_ONLY)
├── gui/
│   ├── RemoteBagMainGUI.java   # 主页 GUI
│   └── RemoteBagContentGUI.java # 内容 GUI
├── listener/
│   └── BagListener.java        # 事件监听器
├── service/
│   ├── RemoteBagService.java   # 背包服务
│   └── BagLockService.java     # 锁定服务
└── util/
    └── SoundUtil.java          # 音效工具
```

### 锁定机制

UltiRemoteBag 实现了一套完整的并发访问控制机制：

```
┌─────────────────────────────────────────────────────────┐
│                    背包访问流程                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  所有者访问自己的背包:                                    │
│  ┌─────────┐    ┌──────────┐    ┌─────────────┐        │
│  │ 请求访问 │───▶│ 检查锁状态 │───▶│ 编辑模式打开 │        │
│  └─────────┘    └──────────┘    └─────────────┘        │
│                       │                                 │
│                       ▼ (管理员持有锁)                   │
│                 ┌───────────┐                           │
│                 │ 阻止访问   │                           │
│                 └───────────┘                           │
│                                                         │
│  管理员访问他人背包:                                      │
│  ┌─────────┐    ┌──────────┐    ┌─────────────┐        │
│  │ 请求访问 │───▶│ 检查锁状态 │───▶│ 编辑模式打开 │        │
│  └─────────┘    └──────────┘    └─────────────┘        │
│                       │                                 │
│                       ▼ (所有者持有锁)                   │
│                 ┌───────────┐                           │
│                 │ 只读模式   │                           │
│                 └───────────┘                           │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**优先级规则：**
- 所有者 (OWNER) 拥有最高优先级
- 管理员 (ADMIN) 在所有者使用时只能只读访问
- 同一时间只有一个用户可以编辑

## 🔧 开发者 API

### 获取服务实例

```java
// 通过 IoC 容器获取服务
RemoteBagService bagService = plugin.getContext().getBean(RemoteBagService.class);
BagLockService lockService = plugin.getContext().getBean(BagLockService.class);
```

### 操作背包数据

```java
// 加载背包
bagService.loadBagIfNeeded(playerUuid);

// 获取背包页列表
List<Integer> pages = bagService.getPlayerBagPages(playerUuid);

// 获取背包内容
ItemStack[] contents = bagService.getBagPage(playerUuid, pageNum);

// 设置背包内容
bagService.setBagPage(playerUuid, pageNum, contents);

// 保存到数据库
bagService.saveBag(playerUuid);
```

### 锁定操作

```java
// 所有者打开背包
BagOpenResult result = lockService.ownerOpen(ownerUuid, pageNum, player);

// 管理员打开背包
BagOpenResult result = lockService.adminOpen(ownerUuid, pageNum, admin);

// 检查结果
if (result.isEditMode()) {
    // 编辑模式
} else if (result.isSuccess()) {
    // 只读模式
    AccessMode mode = result.getAccessMode();
} else {
    // 被阻止
    String message = result.getMessage();
}

// 释放锁
lockService.release(ownerUuid, pageNum, holderUuid);

// 玩家退出时释放所有锁
lockService.releaseAll(playerUuid);
```

### 打开 GUI

```java
// 打开主页
new RemoteBagMainGUI(player, bagService, lockService, config).open();

// 打开内容页
new RemoteBagContentGUI(player, ownerUuid, pageNum, 
    bagService, lockService, config, accessMode).open();
```

## 📊 数据存储

背包数据支持三种存储方式（由 UltiTools-API 配置）：

| 存储方式 | 特点 |
|---------|------|
| **JSON** | 文件存储，人类可读，适合小型服务器 |
| **SQLite** | 本地数据库，良好性能，推荐默认选项 |
| **MySQL** | 远程数据库，适合大型服务器和跨服同步 |

### 数据表结构

```sql
CREATE TABLE ulti_remote_bag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    player_uuid VARCHAR(36) NOT NULL,
    page_number INT NOT NULL,
    contents TEXT,
    last_updated BIGINT,
    UNIQUE KEY uk_player_page (player_uuid, page_number)
);
```

## 🌍 多语言

支持的语言：
- 🇨🇳 简体中文 (zh)
- 🇺🇸 English (en)

语言文件位置：`plugins/UltiTools/UltiRemoteBag/lang/`

添加新语言：创建 `{语言代码}.yml` 文件并翻译所有键值。

## 📝 更新日志

### v2.0.0 (2026-01-12)
**重大更新 - 完全重构**

#### 新增功能
- ✨ 背包锁定机制 - 所有者优先，管理员只读模式
- ✨ 管理员命令 - see, create, delete, clear, list
- ✨ 音效系统 - 可配置的操作音效
- ✨ 经济集成 - Vault 支持，价格递增公式
- ✨ 现代化 GUI - 使用 BasePaginationPage 框架
- ✨ 物品统计 - 显示物品数量和槽位占用

#### 改进
- 🔧 使用 UltiTools-API 6.x GUI 框架重构
- 🔧 服务层分离，更清晰的架构
- 🔧 完善的错误处理和用户反馈
- 🔧 优化的数据缓存机制

#### 修复
- 🐛 修复并发访问可能导致的数据丢失
- 🐛 修复玩家退出时锁未释放的问题

### v1.0.0
- 🎉 初始版本发布

## ❓ FAQ

**Q: 如何增加玩家可用的背包页数？**
> 有两种方式：
> 1. 修改 `max-pages` 配置增加全局上限
> 2. 启用 `permission-based-pages` 并给玩家相应权限

**Q: 管理员查看背包时为什么是只读的？**
> 当背包所有者正在使用时，管理员会自动进入只读模式。等所有者关闭后，点击刷新按钮即可切换为编辑模式。

**Q: 数据存储在哪里？**
> 取决于 UltiTools-API 的配置。默认使用 SQLite，数据库文件在 `plugins/UltiTools/data/` 目录。

**Q: 如何备份玩家背包数据？**
> - SQLite: 备份 `ultitools.db` 文件
> - MySQL: 备份 `ulti_remote_bag` 表
> - JSON: 备份 `data/` 目录下的 JSON 文件

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

- GitHub: [UltiKits/UltiTools-Reborn](https://github.com/UltiKits/UltiTools-Reborn)
- 问题反馈: [Issues](https://github.com/UltiKits/UltiTools-Reborn/issues)

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源协议。

---

**Made with ❤️ by UltiKits Team**
