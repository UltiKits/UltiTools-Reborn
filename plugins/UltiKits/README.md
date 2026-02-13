# UltiKits - 礼包模块 / Kit System Module

[![UltiTools-API](https://img.shields.io/badge/UltiTools--API-6.2.0-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-green)](../../LICENSE)

A standalone kit/gift package module for UltiTools-API. Create, manage, and distribute item kits to players with economy integration, cooldowns, permissions, and GUI browsing.

UltiTools-API 的独立礼包模块。支持创建、管理和分发物品礼包，集成经济系统、冷却时间、权限控制和 GUI 浏览。

## Features / 功能

- **GUI Browser** - Paginated chest GUI for browsing and claiming kits / 分页箱子 GUI 浏览和领取礼包
- **GUI Editor** - In-game kit content editor for admins / 游戏内礼包内容编辑器
- **Economy Integration** - Vault economy support for kit pricing / Vault 经济系统支持
- **Cooldowns** - Per-kit cooldown timers, one-time kits / 每礼包冷却计时器，一次性礼包
- **Level Requirements** - Minimum player level to claim / 最低玩家等级限制
- **Permissions** - Per-kit permission nodes / 每礼包权限节点
- **Commands on Claim** - Execute player and console commands when kits are claimed / 领取时执行命令
- **YAML Configuration** - One file per kit in `kits/` folder / 每礼包一个 YAML 配置文件
- **i18n** - Chinese and English language support / 中英文支持

## Commands / 命令

| Command | Description | Permission |
|---------|-------------|------------|
| `/kits` | Open kit browser GUI / 打开礼包浏览器 | `ultikits.kits.use` |
| `/kits claim <name>` | Claim a kit / 领取礼包 | `ultikits.kits.use` |
| `/kits list` | List all kits / 列出所有礼包 | `ultikits.kits.use` |
| `/kits edit <name>` | Open kit editor GUI / 编辑礼包内容 | `ultikits.kits.admin` |
| `/kits create <name>` | Create kit from inventory / 从物品栏创建礼包 | `ultikits.kits.admin` |
| `/kits delete <name>` | Delete a kit / 删除礼包 | `ultikits.kits.admin` |
| `/kits reload` | Reload kit configurations / 重新加载配置 | `ultikits.kits.admin` |

## Permissions / 权限

| Permission | Description |
|-----------|-------------|
| `ultikits.kits.use` | Basic kit access (browse, claim, list) / 基本礼包访问 |
| `ultikits.kits.admin` | Admin kit management (create, edit, delete, reload) / 管理员礼包管理 |

## Kit Configuration / 礼包配置

Each kit is a YAML file in the `kits/` folder:

```yaml
displayName: "&a&lStarter Kit"
description:
  - "&7Basic items for new players"
  - "&7新手基础物品"
icon: CHEST
price: 0
levelRequired: 0
permission: ""
reBuyable: false
cooldown: 0
playerCommands: []
consoleCommands:
  - "broadcast {player} claimed the starter kit!"
items: "" # Base64 serialized - managed via /kits create or /kits edit
```

| Field | Type | Description |
|-------|------|-------------|
| `displayName` | String | Display name with color codes / 显示名称（支持颜色代码） |
| `description` | List | Lore lines with color codes / 描述行 |
| `icon` | String | Material name for GUI icon / GUI 图标材质名 |
| `price` | Double | Cost to claim (0 = free) / 领取费用（0 = 免费） |
| `levelRequired` | Int | Minimum player level / 最低玩家等级 |
| `permission` | String | Required permission node / 所需权限节点 |
| `reBuyable` | Boolean | Can be claimed multiple times / 是否可重复领取 |
| `cooldown` | Long | Cooldown in seconds between claims / 领取冷却时间（秒） |
| `playerCommands` | List | Commands run as the player / 以玩家身份执行的命令 |
| `consoleCommands` | List | Commands run from console / 以控制台执行的命令 |
| `items` | String | Base64 serialized items (auto-managed) / Base64 序列化物品 |

## Soft Dependencies / 可选依赖

- **Vault** - Economy integration for kit pricing
- **PlaceholderAPI** - Placeholder support in commands

## UltiTools-API Features Used

- `@Service` + constructor injection (IoC)
- `@UltiToolsModule` plugin registration
- `@CmdExecutor` / `@CmdMapping` command system
- `@ConfigEntity` / `@ConfigEntry` / `@Range` config validation
- `BaseDataEntity` + `@Table` / `@Column` ORM
- Query DSL (`operator.query().where("x").eq(y).list()`)
- `DataOperator<T>` for claim persistence
- `UltiToolsPlugin.i18n()` for translations
- obliviate-invs GUI framework
