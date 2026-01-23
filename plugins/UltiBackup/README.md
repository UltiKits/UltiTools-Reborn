# UltiBackup

[![UltiTools Module](https://img.shields.io/badge/UltiTools-Module-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.13--1.21-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://www.oracle.com/java/)

**UltiBackup** 是 UltiTools-API 框架的一个插件模块，为 Minecraft 服务器提供玩家背包备份与恢复功能。支持自动备份、手动备份、GUI 管理和数据完整性校验。

## ✨ 功能特性

### 核心功能
- 💾 **完整备份** - 备份背包、装备、副手、末影箱、经验值和位置信息
- 🔄 **一键恢复** - 通过 GUI 或命令快速恢复任意备份
- 🔍 **备份预览** - 恢复前预览备份内容（背包/装备/末影箱分页查看）
- 🛡️ **数据校验** - MD5 校验和验证，检测文件损坏或篡改

### 自动备份
- ⏱️ **定时备份** - 可配置的自动备份间隔（默认 30 分钟）
- 💀 **死亡备份** - 玩家死亡时自动备份（可开关）
- 🚪 **退出备份** - 玩家离线时自动备份（可开关）
- 📦 **批量备份** - 管理员一键备份所有在线玩家

### 数据管理
- 📊 **备份上限** - 每个玩家最多保留 N 个备份（自动清理旧备份）
- 🗑️ **删除备份** - 支持手动删除不需要的备份
- 🔓 **强制恢复** - 对损坏的备份提供强制恢复选项（带确认提示）

### 用户体验
- 🖥️ **交互式 GUI** - 美观的备份管理界面，支持分页浏览
- ⏳ **操作冷却** - 防止滥用的命令冷却机制
- 🌍 **多语言支持** - 内置中文和英文支持

## 📦 安装

### 依赖项
- **UltiTools-API 6.2.0+** - 核心框架

### 安装步骤
1. 确保已安装 UltiTools-API
2. 将 `UltiBackup.jar` 放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或执行 `/ultitools reload`
4. 编辑配置文件 `plugins/UltiTools/UltiBackup/config/backup.yml`

## ⚙️ 配置说明

### backup.yml

```yaml
# 自动备份配置
auto_backup:
  enabled: true                # 是否启用自动备份
  interval: 30                 # 自动备份间隔（分钟）
  on_death: true               # 玩家死亡时备份
  on_quit: true                # 玩家退出时备份

# 备份上限
max_backups_per_player: 10     # 每个玩家最多保留的备份数量

# 备份内容配置
backup_armor: true             # 是否备份装备
backup_enderchest: true        # 是否备份末影箱
backup_exp: true               # 是否备份经验值
```

## 📜 命令

### 玩家命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/backup` | 打开备份管理 GUI | `ultibackup.use` |
| `/backup list` | 列出我的备份（最多显示 5 个） | `ultibackup.use` |
| `/backup create` | 创建手动备份 | `ultibackup.create` |
| `/backup restore <编号>` | 恢复指定编号的备份 | `ultibackup.use` |
| `/backup restore <编号> force` | 强制恢复损坏的备份 | `ultibackup.use` |
| `/backup help` | 显示帮助信息 | `ultibackup.use` |

### 管理员命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/backup saveall` | 备份所有在线玩家 | `ultibackup.admin` |
| `/backup admin <玩家>` | 查看指定玩家的备份 | `ultibackup.admin` |
| `/backup admin create <玩家>` | 为指定玩家创建备份 | `ultibackup.admin` |

### 命令别名
- `/backup`, `/invbackup`, `/bk`

## 🔑 权限节点

### 玩家权限

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultibackup.use` | 使用备份功能 | true |
| `ultibackup.create` | 创建手动备份 | true |
| `ultibackup.delete` | 删除备份 | true |
| `ultibackup.auto` | 享受自动备份 | true |

### 管理员权限

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultibackup.admin` | 管理员权限（查看/创建他人备份） | op |

## 🏗️ 架构设计

### 项目结构

```
com.ultikits.plugins.backup/
├── UltiBackup.java             # 插件主类
├── commands/
│   └── BackupCommand.java      # 命令执行器
├── config/
│   └── BackupConfig.java       # 配置类
├── entity/
│   ├── BackupMetadata.java     # 备份元数据（数据库）
│   └── BackupContent.java      # 备份内容（文件）
├── gui/
│   ├── BackupGUI.java          # 备份列表 GUI
│   ├── BackupPreviewGUI.java   # 备份预览 GUI
│   └── ForceRestoreConfirmPage.java  # 强制恢复确认页
├── listener/
│   └── BackupListener.java     # 事件监听器
└── service/
    └── BackupService.java      # 备份服务
```

### 冷热数据分离

UltiBackup 采用冷热数据分离架构，优化存储性能：

```
┌─────────────────────────────────────────────────────────────┐
│                     备份数据存储架构                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  热数据 (HOT) - 数据库存储                                   │
│  ┌─────────────────────────────────────────┐               │
│  │ BackupMetadata                          │               │
│  │ - UUID、玩家名、时间戳                    │               │
│  │ - 文件路径、MD5 校验和                    │               │
│  │ - 位置信息、经验等级                      │               │
│  └─────────────────────────────────────────┘               │
│                                                             │
│  冷数据 (COLD) - 文件存储                                    │
│  ┌─────────────────────────────────────────┐               │
│  │ YAML 文件 (backups/{uuid}_{time}.yml)   │               │
│  │ - 序列化的物品数组                        │               │
│  │ - 装备、副手、末影箱内容                  │               │
│  │ - 经验数据                               │               │
│  └─────────────────────────────────────────┘               │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 数据完整性校验

每个备份文件包含 MD5 校验和：

```yaml
# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# DO NOT MODIFY THIS FILE! 请勿修改此文件！
# Checksum: abc123def456...
#
inventory: ...
armor: ...
offhand: ...
enderchest: ...
expLevel: 30
expProgress: 0.85
```

恢复时自动验证校验和，检测到不匹配时提示用户选择强制恢复。

## 🖼️ GUI 预览

### 备份列表
- 分页显示所有备份（每页 45 个）
- 左键点击恢复，右键点击删除
- 显示备份时间、原因、位置信息

### 备份预览
- 三个标签页：背包 / 装备 / 末影箱
- 只读模式，不可修改
- 清晰展示备份内容

### 强制恢复确认
- 校验失败时显示警告
- 需要二次确认才能强制恢复

## 📊 备份原因

| 原因 | 触发条件 |
|------|---------|
| `MANUAL` | 玩家手动创建 |
| `AUTO` | 定时自动备份 |
| `DEATH` | 玩家死亡触发 |
| `QUIT` | 玩家退出触发 |
| `ADMIN` | 管理员创建 |

## 🌍 多语言

支持的语言：
- 🇨🇳 简体中文 (zh)
- 🇺🇸 English (en)

语言文件位置：`plugins/UltiTools/UltiBackup/lang/`

添加新语言：创建 `{语言代码}.yml` 文件并翻译所有键值。

## 📝 更新日志

### v1.0.0
- 🎉 初始版本发布
- ✨ 完整的背包备份功能
- ✨ 自动备份（定时/死亡/退出）
- ✨ GUI 管理界面
- ✨ MD5 数据校验
- ✨ 备份预览功能
- ✨ 管理员命令

## ❓ FAQ

**Q: 备份文件存储在哪里？**
> 备份文件存储在 `plugins/UltiTools/UltiBackup/backups/` 目录，元数据存储在数据库中。

**Q: 如何手动备份数据？**
> - 备份 `backups/` 目录下的所有 YAML 文件
> - 同时备份数据库中的 `ulti_backup_metadata` 表

**Q: 为什么恢复时提示校验失败？**
> 备份文件被手动修改或损坏。如果确认内容无误，可以使用 `force` 参数强制恢复。

**Q: 如何增加每个玩家的备份上限？**
> 修改配置文件中的 `max_backups_per_player` 值。

**Q: 自动备份会影响服务器性能吗？**
> 备份操作使用异步执行，对服务器性能影响很小。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

- GitHub: [UltiKits/UltiTools-Reborn](https://github.com/UltiKits/UltiTools-Reborn)
- 问题反馈: [Issues](https://github.com/UltiKits/UltiTools-Reborn/issues)

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源协议。

---

**Made with ❤️ by UltiKits Team**
