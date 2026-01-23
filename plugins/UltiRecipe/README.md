# UltiRecipe

**Minecraft 服务器自定义配方插件** - 通过 YAML 配置轻松创建自定义合成配方，无需编写代码。

[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://www.oracle.com/java/)
[![Spigot](https://img.shields.io/badge/Spigot-1.13--1.21-yellow.svg)](https://www.spigotmc.org/)
[![UltiTools](https://img.shields.io/badge/UltiTools-6.2.0+-blue.svg)](https://github.com/UltiKits/UltiTools-Reborn)

## ✨ 功能特性

### 🔧 核心功能

- **YAML 配置** - 使用简单的 YAML 格式定义配方，无需编写 Java 代码
- **有序配方** - 支持 3x3 工作台有序合成配方 (ShapedRecipe)
- **自定义物品** - 支持自定义产出物品的名称、描述和数量
- **颜色代码** - 支持 `&` 颜色代码，让物品名称和描述更加个性化

### 🚀 易用性
- **热重载** - 使用 `/recipe reload` 无需重启服务器即可重载配方
- **配方列表** - 使用 `/recipe list` 查看所有已注册的配方
- **即插即用** - 放入插件文件夹，自动生成示例配置

### 🔒 兼容性

- **UltiTools 生态** - 完美集成 UltiTools-API 6.2.0+ 框架
- **版本支持** - 支持 Minecraft 1.13 - 1.21 所有版本
- **原版材料** - 支持所有 Minecraft 原版材料作为配方材料

## 📦 安装

1. 确保已安装 [UltiTools-API](https://github.com/UltiKits/UltiTools-Reborn) 6.2.0 或更高版本
2. 下载最新版本的 UltiRecipe
3. 将 JAR 文件放入 `plugins/UltiTools/plugins/` 目录
4. 重启服务器或执行 `/ul reload`
5. 编辑 `plugins/UltiTools/UltiRecipe/config/recipes.yml` 添加自定义配方

## 🎮 命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/recipe list` | `ultirecipe.admin` | 列出所有已注册的配方 |
| `/recipe reload` | `ultirecipe.admin` | 重载配方配置 |
| `/recipe count` | `ultirecipe.admin` | 显示已注册的配方数量 |

## ⚙️ 配置

### 配置文件结构

配置文件位于 `plugins/UltiTools/UltiRecipe/config/recipes.yml`：

```yaml
enabled: true  # 是否启用自定义配方

recipes:
  # 配方名称（唯一标识符）
  golden_egg:
    # 产出物品配置
    output:
      material: EGG           # 物品材料
      amount: 1               # 产出数量
      name: "&e&l金苹果蛋"     # 自定义名称（支持颜色代码）
      lore:                   # 自定义描述
        - "&7由苹果和木头合成的神奇蛋"
    
    # 配方形状（3行，每行3个字符）
    shape:
      - "xxx"
      - "xyx"
      - "y y"
    
    # 材料映射（字符 -> 材料名称）
    ingredients:
      x: APPLE
      y: DARK_OAK_WOOD
```

### 配方形状说明

- 形状由 3 行字符串组成，每行 3 个字符
- 空格 ` ` 表示该位置不需要放置物品
- 其他字符在 `ingredients` 中定义对应的材料

### 示例配方

**钻石剑配方：**
```yaml
recipes:
  custom_sword:
    output:
      material: DIAMOND_SWORD
      amount: 1
      name: "&b&l神圣之剑"
      lore:
        - "&7传说中的神器"
        - "&7攻击力 +100"
    shape:
      - " D "
      - " D "
      - " S "
    ingredients:
      D: DIAMOND
      S: STICK
```

**压缩铁块配方：**
```yaml
recipes:
  compressed_iron:
    output:
      material: IRON_BLOCK
      amount: 9
    shape:
      - "III"
      - "III"
      - "III"
    ingredients:
      I: IRON_INGOT
```

## 🛠️ 开发者信息

UltiRecipe 使用 UltiTools-API 框架开发，充分利用了以下特性：

- `@UltiToolsModule` - 模块自动扫描和注册
- `@Service` - IoC 容器管理的服务类
- `@ConfigEntity` - 配置文件自动加载和保存
- `@CmdExecutor` - 命令自动注册
- `@Autowired` - 依赖自动注入

### 项目结构

```
UltiRecipe/
├── pom.xml
├── README.md
└── src/main/
    ├── java/com/ultikits/plugins/recipe/
    │   ├── UltiRecipe.java         # 主类
    │   ├── config/
    │   │   └── RecipeConfig.java   # 配置实体
    │   ├── service/
    │   │   └── RecipeService.java  # 配方服务
    │   └── commands/
    │       └── RecipeCommand.java  # 命令
    └── resources/
        ├── plugin.yml
        └── lang/
            ├── zh.json             # 中文语言文件
            └── en.json             # 英文语言文件
```

## 📄 许可证

本项目是 UltiKits 生态系统的一部分，遵循 MIT 许可证。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

- 报告问题：[GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues)
- 参与开发：Fork 仓库并提交 PR

## 📞 支持

- 文档：[UltiKits Wiki](https://wiki.ultikits.com)
- QQ 群：`246078389`
- Discord：[UltiKits Server](https://discord.gg/ultikits)
