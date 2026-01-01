# UltiTools-API Wiki

<div align="center">
<img src="https://github.com/UltiKits/UltiTools-Reborn/assets/62180110/f5e8e7d3-e97d-4d37-a9ab-ba3722dc6faa" width="96" height="96"/>
<h2>UltiTools 6 - Minecraft Spigot 插件开发框架</h2>
</div>

---

## 📖 目录

| 文档 | 描述 |
|------|------|
| [项目总览](./README.md) | 项目介绍、特性概述 |
| [架构设计](./ARCHITECTURE.md) | 系统架构、模块关系 |
| [IoC 容器](./modules/IOC_CONTAINER.md) | SimpleContainer 依赖注入系统 |
| [命令系统](./modules/COMMAND_SYSTEM.md) | 注解驱动命令开发 |
| [数据存储](./modules/DATA_STORAGE.md) | ORM 与多数据源支持 |
| [配置管理](./modules/CONFIG_SYSTEM.md) | 配置实体与自动序列化 |
| [GUI 系统](./modules/GUI_SYSTEM.md) | 背包界面开发 |
| [WebSocket 集成](./modules/WEBSOCKET.md) | UltiPanel 远程管理 |
| [快速入门](./tutorials/QUICK_START.md) | 创建第一个 UltiTools 模块 |
| [完整示例](./tutorials/EXAMPLES.md) | 实际开发案例 |
| [API 参考](./api/INDEX.md) | 核心 API 文档 |

---

## 🎯 什么是 UltiTools-API？

**UltiTools-API** 是一个面向 **Minecraft Spigot** 服务器的 **注解驱动插件开发框架**。它提供了类似 Spring 的依赖注入、ORM 数据持久化、注解驱动命令注册等现代化开发特性，大大简化了 Bukkit 插件的开发流程。

### 核心特性

| 特性 | 描述 |
|------|------|
| **注解驱动** | 通过 `@CmdExecutor`、`@Service`、`@Table` 等注解自动注册组件 |
| **IoC 容器** | 轻量级依赖注入容器 `SimpleContainer`，支持 `@Autowired` 自动装配 |
| **ORM 支持** | 统一的数据访问接口，支持 MySQL、SQLite、JSON 三种存储方式 |
| **命令映射** | 类似 Spring MVC 的命令处理，自动参数解析与类型转换 |
| **GUI 框架** | 基于模板方法模式的背包界面开发基类 |
| **远程管理** | WebSocket 集成 UltiPanel，支持服务器状态监控、远程命令执行 |
| **国际化** | 内置多语言支持，自动加载语言文件 |

---

## 📊 技术规格

| 项目 | 值 |
|------|------|
| **版本** | 6.2.0 |
| **Java 版本** | 1.8+ |
| **Minecraft 版本** | 1.8 - 1.21 |
| **构建工具** | Maven |
| **许可证** | MIT |
| **Maven Central** | `com.ultikits:UltiTools-API` |

---

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version>6.2.0</version>
    <scope>provided</scope>
</dependency>
```

### 2. 创建插件主类

```java
@UltiToolsModule(scanBasePackages = {"com.example.myplugin"})
public class MyPlugin extends UltiToolsPlugin {
    
    @Override
    public boolean registerSelf() {
        // 插件启动逻辑
        return true;
    }
    
    @Override
    public void unregisterSelf() {
        // 插件卸载逻辑
    }
}
```

### 3. 创建命令

```java
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
@CmdExecutor(alias = {"hello"}, permission = "myplugin.hello")
public class HelloCommand extends AbstractCommandExecutor {
    
    @CmdMapping(format = "<name>")
    public void sayHello(@CmdSender Player player, @CmdParam("name") String name) {
        player.sendMessage("Hello, " + name + "!");
    }
}
```

### 4. 创建数据实体

```java
@Table("player_data")
public class PlayerData extends AbstractDataEntity {
    
    @Column("uuid")
    private String uuid;
    
    @Column("balance")
    private double balance;
}
```

---

## 📦 项目结构

```
UltiTools-Reborn/
├── src/main/java/com/ultikits/ultitools/
│   ├── UltiTools.java              # 主插件入口
│   ├── abstracts/                  # 抽象基类
│   │   ├── UltiToolsPlugin.java    # 模块基类
│   │   ├── AbstractCommandExecutor.java
│   │   ├── AbstractDataEntity.java
│   │   ├── AbstractConfigEntity.java
│   │   └── gui/                    # GUI 基类
│   ├── annotations/                # 框架注解
│   │   ├── @Service, @Component, @Autowired
│   │   ├── @Table, @Column
│   │   ├── @ConfigEntity, @ConfigEntry
│   │   └── command/                # 命令注解
│   ├── context/                    # IoC 容器
│   │   └── SimpleContainer.java
│   ├── interfaces/                 # 核心接口
│   │   ├── DataOperator.java       # 数据操作接口
│   │   ├── DataStore.java          # 数据存储接口
│   │   └── impl/                   # 接口实现
│   ├── manager/                    # 管理器
│   │   ├── PluginManager.java      # 模块管理
│   │   ├── CommandManager.java     # 命令管理
│   │   ├── ConfigManager.java      # 配置管理
│   │   └── ServerMonitorManager.java # 服务器监控
│   └── websocket/                  # WebSocket 集成
│       └── UltiPanelWebSocketClient.java
```

---

## 🔗 相关链接

- **官方文档**: [https://dev.ultikits.com/](https://dev.ultikits.com/)
- **GitHub**: [https://github.com/UltiKits/UltiTools-Reborn](https://github.com/UltiKits/UltiTools-Reborn)
- **Discord**: [加入社区](https://discord.gg/6TVRRF47)
- **SpigotMC**: [SpigotMC 页面](https://www.spigotmc.org/resources/ultitools.85214/)

---

## 📝 许可证

本项目采用 [MIT 许可证](../../LICENSE)。

---

> **下一步**: 阅读 [架构设计](./ARCHITECTURE.md) 了解系统整体结构
