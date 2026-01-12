# UltiSideBar

[![UltiTools](https://img.shields.io/badge/UltiTools-6.0+-blue.svg)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8--1.21-green.svg)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://www.java.com/)

一个功能强大的 Minecraft 服务器侧边栏插件，基于 UltiTools-API 框架开发，支持 PlaceholderAPI 变量和玩家偏好持久化。

## ✨ 功能特性

- 🎨 **自定义标题和内容** - 完全可配置的侧边栏标题和多行内容
- 🔄 **PlaceholderAPI 支持** - 支持所有 PAPI 变量，实现动态数据显示
- 💾 **偏好持久化** - 玩家的侧边栏开关状态自动保存到数据库
- ⚡ **性能优化** - 内容缓存机制，仅在内容变化时更新，减少网络开销
- 🌍 **世界黑名单** - 可配置在特定世界禁用侧边栏
- 🔥 **热重载** - 支持 `/sidebar reload` 实时重载配置，无需重启服务器
- 🌐 **多语言支持** - 内置中英文语言文件，支持自定义翻译

## 📦 安装

### 前置依赖

- **UltiTools-API** 6.0 或更高版本
- **PlaceholderAPI** (可选，但强烈推荐)

### 安装步骤

1. 下载 `UltiSideBar.jar`
2. 将文件放入服务器的 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或执行 `/ultitools reload`
4. 编辑 `plugins/UltiTools/UltiSideBar/config/sidebar.yml` 配置文件
5. 执行 `/sidebar reload` 应用配置

## ⚙️ 配置

配置文件位于 `plugins/UltiTools/UltiSideBar/config/sidebar.yml`

```yaml
# 启用侧边栏
enabled: true

# 侧边栏标题（支持颜色代码和变量）
title: "&6&l我的服务器"

# 更新间隔（tick，20 tick = 1秒）
update-interval: 20

# 侧边栏内容（支持 PlaceholderAPI 变量）
lines:
  - "&7欢迎, &f%player_name%"
  - ""
  - "&e在线人数: &f%server_online%/%server_max_players%"
  - "&e世界: &f%world_name%"
  - ""
  - "&e金币: &f%vault_eco_balance_formatted%"
  - "&ePing: &f%player_ping%ms"
  - ""
  - "&7服务器时间"
  - "&f%server_time_hh:mm:ss%"
  - ""
  - "&6play.example.com"

# 禁用侧边栏的世界
world-blacklist:
  - "world_event"

# 玩家默认启用侧边栏
default-enabled: true
```

### 颜色代码

使用 `&` 符号加颜色代码：

| 代码 | 颜色 | 代码 | 样式 |
|------|------|------|------|
| &0 | 黑色 | &l | 粗体 |
| &1 | 深蓝 | &m | 删除线 |
| &2 | 深绿 | &n | 下划线 |
| &3 | 深青 | &o | 斜体 |
| &4 | 深红 | &r | 重置 |
| &5 | 紫色 | | |
| &6 | 金色 | | |
| &7 | 灰色 | | |
| &8 | 深灰 | | |
| &9 | 蓝色 | | |
| &a | 浅绿 | | |
| &b | 青色 | | |
| &c | 红色 | | |
| &d | 粉色 | | |
| &e | 黄色 | | |
| &f | 白色 | | |

### 常用 PlaceholderAPI 变量

| 变量 | 描述 |
|------|------|
| `%player_name%` | 玩家名称 |
| `%player_ping%` | 玩家延迟 |
| `%player_health%` | 玩家生命值 |
| `%player_food_level%` | 玩家饥饿值 |
| `%server_online%` | 在线人数 |
| `%server_max_players%` | 最大人数 |
| `%server_tps%` | 服务器 TPS |
| `%world_name%` | 当前世界名 |
| `%vault_eco_balance%` | 玩家余额 |
| `%vault_eco_balance_formatted%` | 格式化余额 |
| `%server_time_hh:mm:ss%` | 服务器时间 |

更多变量请参考 [PlaceholderAPI Wiki](https://github.com/PlaceholderAPI/PlaceholderAPI/wiki/Placeholders)

## 📝 命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/sidebar` | 显示帮助信息 | `ultisidebar.toggle` |
| `/sidebar toggle` | 切换侧边栏开关 | `ultisidebar.toggle` |
| `/sidebar on` | 开启侧边栏 | `ultisidebar.toggle` |
| `/sidebar off` | 关闭侧边栏 | `ultisidebar.toggle` |
| `/sidebar reload` | 重载配置文件 | `ultisidebar.admin` |

**命令别名:** `/sb`

## 🔐 权限

| 权限节点 | 描述 | 默认 |
|----------|------|------|
| `ultisidebar.toggle` | 允许玩家切换自己的侧边栏 | 所有玩家 |
| `ultisidebar.admin` | 允许重载插件配置 | OP |

## 📁 数据存储

玩家的侧边栏偏好设置会自动保存到 UltiTools 配置的数据源中（JSON/SQLite/MySQL），无需额外配置。

数据表结构：
- **表名:** `sidebar_preferences`
- **字段:** `player_uuid` (玩家UUID), `enabled` (是否启用)

## 🔧 技术细节

### 性能优化

- **内容缓存:** 每个玩家的侧边栏内容会被缓存，只有当 PlaceholderAPI 变量解析结果发生变化时才会更新 Scoreboard
- **异步检测:** PlaceholderAPI 在主线程执行，但内容比较在内存中完成，开销极小

### 配置热重载

执行 `/sidebar reload` 时会：
1. 重新加载 YAML 配置文件
2. 清空所有玩家的内容缓存
3. 强制刷新所有在线玩家的侧边栏

### 世界切换处理

- 玩家进入黑名单世界时，侧边栏自动隐藏
- 玩家离开黑名单世界时，侧边栏自动恢复（如果玩家未手动关闭）

## 📋 更新日志

### v1.0.0
- 🎉 初始版本发布
- ✅ PlaceholderAPI 变量支持
- ✅ 玩家偏好持久化存储
- ✅ 内容缓存性能优化
- ✅ 世界黑名单功能
- ✅ 配置热重载命令
- ✅ 多语言支持 (中/英)

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。

## 🔗 相关链接

- [UltiTools-API 文档](https://doc.ultikits.com/)
- [UltiKits 官网](https://www.ultikits.com/)
- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
