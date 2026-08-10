# 兼容性与版本策略

本文件说明 `com.ultikits:UltiTools-API` 的版本号含义、废弃与移除策略，
以及当前正在进行的移除动作。面向下游模块作者。

## 版本号语义

**本项目的版本号是产品阶段信号，不是严格的 semver 契约。**

- **PATCH**（例如 6.2.4 → 6.2.5）：小更新与紧急修复。不移除公开 API。
- **MINOR**（例如 6.2.x → 6.3.0）：功能演进。**可能包含公开 API 的移除**，分两种情形：
  - **下游引用为零的类型**——直接移除，每一项记录在下方清单中并附实测依据。
  - **仍有下游用户的类型**——仅在满足两个条件后移除：提供书面迁移指引，
    并至少提前一个 PATCH 版本发出移除预告。当前唯一属于此类的是
    `AbstractCommandExecutor`，见下方专节。
- **MAJOR**：保留给框架层面的方向性变更。不会仅仅为了清理废弃 API 而发布。

如果你需要严格的二进制兼容保证，请锁定具体的 PATCH 版本。

## 依赖声明

Maven：

```xml
<dependency>
    <groupId>com.ultikits</groupId>
    <artifactId>UltiTools-API</artifactId>
    <version><!-- 见 GitHub Releases --></version>
    <scope>provided</scope>
</dependency>
```

Gradle：

```groovy
compileOnly 'com.ultikits:UltiTools-API:<version>'
```

**务必使用 `provided` / `compileOnly`。** 发布到 Maven Central 的 POM 经
flatten-maven-plugin 处理后不含依赖声明，因此把 UltiTools-API 放进编译期以外的范围
（Maven `compile`、Gradle `implementation`）不会给你带来任何传递依赖，
却会在你的构建带有 shade / shadow 步骤时，把整个 shaded 框架打进你的模块 JAR，
在运行时与服务器上已有的 UltiTools 冲突。

## 6.3.0 的移除清单

以下类型将在 6.3.0 移除。每一项都附有移除依据——
2026-08-10 对 UltiKits 组织下 17 个下游模块仓库的引用量实测（排除测试目录）。
这 17 个中有 15 个是继承 `UltiToolsPlugin` 的插件模块，另两个分别是父 POM
（`ultikits-module-parent`）与一个非插件项目；引用量统计覆盖全部 17 个。

| 类型 | 下游引用 | 替代方案 |
|---|---|---|
| `AbstractCommendExecutor` | 0 | `abstracts.command.BaseCommandExecutor` |
| `AbstractDataEntity` | 0 | `abstracts.data.BaseDataEntity<ID>` |
| `abstracts.guis.PagingPage` | 0 | `abstracts.gui.BasePaginationPage` |
| `abstracts.guis.OkCancelPage` | 0 | `abstracts.gui.BaseConfirmationPage` |
| `interfaces.VersionWrapper` 的废弃方法 | 0 | `utils.XVersionUtils` |
| `utils.SecurityPolicy`（重命名） | 0 | `PluginScanFilter`（不提供运行时约束） |

若你的模块不在上述 17 个仓库中且引用了其中任何一项，
请在 6.3.0 发布前提 issue，我们会重新评估。

## `AbstractCommandExecutor` 的迁移

`abstracts.AbstractCommandExecutor` 标记为 `@Deprecated(since = "6.2.0", forRemoval = true)`，
是唯一有真实下游用户的废弃类型（实测 14 个文件）。
它将在 6.3.0 移除，**下游迁移会与移除在同一个版本周期内协调完成**。

迁移到 `abstracts.command.BaseCommandExecutor`：

1. 改继承：`extends AbstractCommandExecutor` → `extends BaseCommandExecutor`。
2. 实现新增的抽象方法 `protected void handleHelp(CommandSender sender)`。
3. `@CmdMapping` / `@CmdParam` / `@CmdTarget` / `@CmdCD` / `@UsageLimit` 语义不变。
4. 通过 `@CmdExecutor` 包扫描注册的命令需要改为显式注册——
   扫描路径会把新基类强转为旧基类。走 IoC 路由的主加载路径不受影响。

新基类目前有两个已知缺口，修复排期**不同**：

| 缺口 | 排期 |
|---|---|
| `@CmdMapping(format = "")` 的裸命令不可执行 | **6.2.5** |
| 参数级 tab 补全尚未接线 | **6.3.0**（安排在迁移动作之前） |

迁移时机取决于你是谁：

- **上述 17 个仓库内的模块**由维护者在 6.3.0 周期内统一迁移，你不需要自己动手。
- **仓库外的第三方模块**应当在 **6.2.5 期间**就完成迁移。这样做要接受一个代价：
  参数级 tab 补全要到 6.3.0 才接线，在此之前迁移过去的命令只在第一个参数位补全字面量。
  但这是唯一能给你留出真实过渡期的做法——补齐 tab 补全的版本（6.3.0）
  同时也是移除旧基类的版本，等到那时再迁移就没有缓冲了。

## 支持范围

| 项 | 值 |
|---|---|
| 服务端 | Paper（不支持 plain Spigot——代码全面使用 Adventure `Component`） |
| 构建 JDK | 21 |
| 字节码目标 | Java 8（`-source`/`-target`，非 `--release`） |
| `plugin.yml` 的 `api-version` | `1.19`（Bukkit API 层级，与上面两项无关） |
| 模块 `plugin.yml` 的 `api-version` | `620`（UltiTools API 层级，与 Bukkit 的同名字段无关） |

## 反馈

对本策略有异议，或你的模块受到上述移除影响，
请在 [GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues) 提出。
