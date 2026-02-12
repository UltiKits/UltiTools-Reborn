# UltiMenu

[![UltiTools-API](https://img.shields.io/badge/UltiTools--API-6.2.0-blue)](https://github.com/UltiKits/UltiTools-Reborn)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.8--1.21-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-8+-orange)](https://www.java.com/)

UltiMenu 是基于 UltiTools-API 框架开发的自定义菜单插件模块，允许服务器管理员通过 YAML 配置文件创建任意数量的 GUI 菜单，支持按钮命令、经济扣费、子菜单跳转、物品绑定和 PlaceholderAPI 变量。

## ✨ 功能特性

### 📋 菜单系统

- **多文件菜单** — 每个菜单一个 `.yml` 文件，放在 `menus/` 目录下
- **可配置大小** — 支持 9/18/27/36/45/54 格（1-6行）
- **颜色代码** — 标题和按钮名称支持 `&` 颜色代码
- **PlaceholderAPI** — 标题、按钮名称、Lore、命令均支持 PAPI 变量
- **权限控制** — 菜单和按钮均可设置独立权限
- **点击防抖** — 可配置的全局点击冷却（默认 200ms）

### 🔘 按钮功能

| 功能 | YAML 键 | 描述 |
|------|---------|------|
| 物品图标 | `item` | Material 名称（如 `DIAMOND_SWORD`） |
| 显示名称 | `name` | 支持颜色代码和 PAPI 变量 |
| 描述文本 | `lore` | 多行文本列表 |
| 格子位置 | `position` | 0-53（从左上角开始） |
| 玩家命令 | `player-commands` | 以玩家身份执行的命令列表 |
| 控制台命令 | `console-commands` | 以控制台身份执行的命令列表 |
| 经济扣费 | `price` | 点击按钮扣除的金额（需 Vault） |
| 子菜单 | `open-menu` | 点击后打开另一个菜单 |
| 点击关闭 | `close-on-click` | 点击后是否关闭菜单（默认 true） |
| 按钮权限 | `permission` | 使用此按钮所需的权限 |
| 模型数据 | `custom-model-data` | 自定义模型数据（资源包） |

### 📎 物品绑定

| YAML 键 | 描述 |
|---------|------|
| `bind-item` | 绑定的物品类型（如 `COMPASS`） |
| `bind-name` | 绑定的物品名称（可选） |
| `bind-lore` | 绑定的物品 Lore 关键词（可选） |

玩家右键点击匹配的物品时自动打开对应菜单。

### 🎮 命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/menu <名称>` | 打开指定菜单 | `ultikits.menu.use` |
| `/menu open <名称>` | 打开指定菜单（显式） | `ultikits.menu.use` |
| `/menu list` | 列出所有可用菜单 | `ultikits.menu.use` |
| `/menu reload` | 重新加载所有菜单配置 | `ultikits.menu.admin` |

## 📦 安装

1. 确保服务器已安装 [UltiTools-API](https://github.com/UltiKits/UltiTools-Reborn) 6.2.0+
2. 将 `UltiMenu-1.0.0.jar` 放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或使用 `/ul reload` 重载插件
4. 首次启动会在 `menus/` 目录生成 `example.yml` 示例菜单

**或通过 UPM 安装:**
```
/upm install UltiMenu
```

## ⚙️ 配置文件

### 主配置 (`config/config.yml`)

```yaml
# 点击冷却时间（毫秒），防止快速连点
click_cooldown_ms: 200
```

### 菜单配置示例 (`menus/example.yml`)

```yaml
# 菜单标题，支持颜色代码和 PlaceholderAPI
title: "&6&l服务器菜单"

# 菜单大小（必须是9的倍数：9/18/27/36/45/54）
size: 27

# 打开菜单所需权限（可选）
permission: ""

# 物品绑定（可选）— 右键点击匹配物品打开菜单
bind-item: COMPASS
bind-name: "&6服务器菜单"

# 按钮定义
buttons:
  info:
    item: BOOK
    position: 11
    name: "&e&l服务器信息"
    lore:
      - "&7欢迎来到服务器！"
      - "&7当前在线: &a%server_online%"
    close-on-click: false

  spawn:
    item: ENDER_PEARL
    position: 13
    name: "&b&l回到出生点"
    player-commands:
      - "spawn"
    close-on-click: true

  vip-shop:
    item: GOLD_INGOT
    position: 15
    name: "&6&lVIP 商店"
    price: 100.0
    console-commands:
      - "give {player} diamond 1"
    lore:
      - "&7购买一颗钻石"
      - "&7价格: &e$100"
    permission: "server.vip"

  rules:
    item: PAPER
    position: 22
    name: "&c&l服务器规则"
    open-menu: "rules"
```

## 🔐 权限节点

| 权限 | 描述 | 默认 |
|------|------|------|
| `ultikits.menu.use` | 使用 /menu 命令打开菜单 | 玩家 |
| `ultikits.menu.admin` | 使用 /menu reload 重载菜单 | OP |

菜单和按钮可通过 `permission` 字段设置额外权限。

## 🔌 软依赖

| 插件 | 用途 |
|------|------|
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 在菜单标题、按钮名称、Lore、命令中使用变量 |
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | 按钮经济扣费功能 |

## 🌍 多语言支持

UltiMenu 支持多语言，语言文件位于 `lang/` 目录：
- `lang/zh.json` - 简体中文
- `lang/en.json` - English

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📞 支持

- GitHub Issues: [https://github.com/UltiKits/UltiTools-Reborn/issues](https://github.com/UltiKits/UltiTools-Reborn/issues)
- 文档: [https://doc.ultikits.com](https://doc.ultikits.com)
