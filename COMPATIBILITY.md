# 兼容性与版本策略

本文件说明 `com.ultikits:UltiTools-API` 的版本号含义、废弃与移除策略，
以及当前正在进行的移除动作。面向下游模块作者。

## 版本号语义

**本项目的版本号是产品阶段信号，不是严格的 semver 契约。**

- **PATCH**（例如 6.2.4 → 6.2.5）：小更新与紧急修复。不移除公开 API。
- **MINOR**（例如 6.2.x → 6.3.0）：功能演进。**可能包含公开 API 的移除**。
- **MAJOR**：保留给框架层面的方向性变更。不会仅仅为了清理废弃 API 而发布。

### 一个公开 API 何时可以被移除

只有同时满足以下两条，才会被列入移除清单：

1. 它在代码里带有 `@Deprecated(since = "…", forRemoval = true)`；
2. 从**首个带上这个标注的发布**算起，已经跨过至少一个 MINOR 版本。

第 2 条的起算点是**警告真正发到你手里的那个版本**，不是标注里 `since` 写的值——
`since` 表达的是「我们认为它何时该被废弃」，可以回填；起算点不行。
下方清单为每一项标出了这个版本。

这两条是移除的**全部依据**。下游引用量不再作为依据：
清单里附带的引用量实测只是参考信息，它能告诉你哪几项的迁移成本真实存在，
但「零引用」只证明我们能看到的仓库里没人用，证明不了组织外的第三方没在用。

为什么用 `forRemoval` 而不是普通的 `@Deprecated`：javac 的 `-Xlint:removal` 自 JDK 9 起
**默认开启**，`-Xlint:deprecation` **默认关闭**。带 `forRemoval` 的 API 会在你的构建里
逐处点名报警；只带普通 `@Deprecated` 的则只有一句不含 API 名、不含行号的笼统提示。
所以我们把「你确实被点名警告过」当作可以移除的前提。

### 与 semver 的两处明确偏离

格式是 `MAJOR.MINOR.PATCH`，容易让人套用 [semver](https://semver.org/spec/v2.0.0.html) 的预期。
以下两点我们和 semver 原文不一致，特此点名：

- **移除时机**。semver 的做法是废弃后要等到下一个 MAJOR 才移除；
  本项目在跨过一个 MINOR 之后就会在 MINOR 移除。如果你需要严格的二进制兼容保证，
  请锁定具体的 PATCH 版本。
- **不采用 semver 条款 6 的宽松解读**。该条款允许把「修正错误行为」当作可以走 PATCH 的
  bug fix。本项目不这么做：任何会改变下游运行时行为的变更，一律按下方
  [行为变更](#行为变更) 一节处理，不会因为「这是在修 bug」就绕开迁移期。

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

**这份清单按代码里的 `@Deprecated(forRemoval = true)` 标注生成，不再手工维护。**
标注是唯一的真相来源；文档不会再列出代码里没有标注的类型。
标为 `@ApiStatus.Internal` 的包与类型不在此列——它们本就不属于公开 API，
移除它们不构成兼容性事件。

「移除预告发自」是该 API 首次带上 `forRemoval` 标注的发布版本，
也就是你的构建第一次点名警告它的版本。

### 命令

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `abstracts.AbstractCommandExecutor` | 6.2.1 | `abstracts.command.BaseCommandExecutor` | **15 个文件 / 6 个仓库** |
| `abstracts.AbstractCommendExecutor`（拼写错误的空 shim） | 6.2.5 | 同上 | 0 |
| `manager.CommandManager.register(CommandExecutor, …)` 的两个重载 | 6.2.5 | `register(UltiToolsPlugin, Class, String, String, String…)` | 0 |
| `annotations.command.@OptionalParam` | 6.2.5 | 无替代——该注解从未实现，标注它不影响解析；改为每种可接受的参数形态各写一条 `@CmdMapping` | 0 |

### 版本适配

`interfaces.VersionWrapper` 整簇由 `utils.XVersionUtils` 取代，后者是前者的超集。

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `interfaces.VersionWrapper` 接口及其 14 个 default 方法 | 6.2.5 | `utils.XVersionUtils` | 0 |
| `interfaces.impl.DefaultVersionWrapper` | 6.2.5 | 同上 | 0 |
| `UltiTools.getVersionWrapper()` | 6.2.5 | 同上 | 0 |
| `abstracts.UltiToolsPlugin.getVersionWrapper()`（static） | 6.2.5 | 同上 | 0 |

### 数据与界面基类

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `abstracts.AbstractDataEntity` | 6.2.1 | `abstracts.data.BaseDataEntity<ID>` | 0 |
| `abstracts.guis.PagingPage` | 6.2.1 | `abstracts.gui.BasePaginationPage` | 0 |
| `abstracts.guis.OkCancelPage` | 6.2.1 | `abstracts.gui.BaseConfirmationPage` | 0 |

### 监听器与注册

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `interfaces.TempListener.player(Class)` 及 `TempListener.PlayerTempListenerBuilder` | 6.2.5 | `TempListener.common(Class)`，用 `filter(Function)` 收窄到玩家事件 | 0 |
| `interfaces.impl.PlayerTempListener` | 6.2.5 | 同上 | 0 |
| `manager.ListenerManager.register(UltiToolsPlugin, Listener)` | 6.2.5 | `register(UltiToolsPlugin, Class)`——旧重载接收已构造好的实例，因此不执行依赖注入 | 0 |

### 插件基类

| 类型 / 成员 | 移除预告发自 | 替代方案 | 下游引用（参考） |
|---|---|---|---|
| `abstracts.UltiToolsPlugin(String, String, List, List, int, String)` 六参数构造 | 6.2.5 | 七参数构造，显式传入 `resourceFolderPath`（六参数重载把它硬编码成 `<dataFolder>/pluginConfig/<插件名>`） | 0 |

引用量口径：2026-08-14 对 UltiKits 组织下 17 个模块仓库、4 个工具项目与 Libraries
共 310 个 Java 文件的实测，排除测试目录与构建产物，只统计 import 与 extends。
`6.2.1` 这个起算点取自 git 标签中可验证的最早发布；标注里写的 `since = "6.2.0"`
对应的版本发布到了 Maven Central 但未打标签，无法从仓库历史核对。

**若你的模块引用了其中任何一项，请在 6.3.0 发布前提 issue，我们会重新评估。**
上表的引用量不是移除依据，也不构成「没人在用」的结论。

## `AbstractCommandExecutor` 的迁移

`abstracts.AbstractCommandExecutor` 是清单里唯一有真实下游用户的类型（实测 15 个文件）。
它将在 6.3.0 移除，**下游迁移会与移除在同一个版本周期内协调完成**。

迁移到 `abstracts.command.BaseCommandExecutor`：

1. 改继承：`extends AbstractCommandExecutor` → `extends BaseCommandExecutor`。
2. 实现新增的抽象方法 `protected void handleHelp(CommandSender sender)`。
3. `@CmdMapping` / `@CmdParam` / `@CmdTarget` / `@CmdCD` / `@UsageLimit` 语义不变。
4. 通过 `@CmdExecutor` 包扫描注册的命令需要改为显式注册——
   扫描路径会把新基类强转为旧基类。走 IoC 路由的主加载路径不受影响。

拼写错误的空壳 `AbstractCommendExecutor` 继承自 `AbstractCommandExecutor`，
因此它**必须与父类在同一个版本移除**，不存在单独保留的选项。

新基类目前有一个已知缺口：

| 缺口 | 排期 |
|---|---|
| 参数级 tab 补全尚未接线 | **6.3.0**（排在维护者对下游仓库的迁移之前） |

（`@CmdMapping(format = "")` 的裸命令不可执行，此前排期 6.2.5，**已在 6.2.5 修复**。）

迁移时机取决于你是谁：

- **UltiKits 组织内的模块**由维护者在 6.3.0 周期内统一迁移，你不需要自己动手。
- **组织外的第三方模块**应当在 **6.2.5 期间**就完成迁移。这样做要接受一个代价：
  参数级 tab 补全要到 6.3.0 才接线，在此之前迁移过去的命令只在第一个参数位补全字面量。
  但这是唯一能给你留出真实过渡期的做法——补齐 tab 补全的版本（6.3.0）
  同时也是移除旧基类的版本，等到那时再迁移就没有缓冲了。

## 行为变更

有一类变更方法签名一动不动，却会改变你的模块在运行时的表现：
某个方法从静默返回改成抛异常、某个默认值翻转、缺少可选依赖时从降级运行改成加载失败。
签名比对工具查不出这类变更，上面的移除清单也覆盖不到它们。本节说明我们怎么处理。

### 三类兼容性

沿用 [OpenJDK CSR](https://wiki.openjdk.org/display/csr/Kinds+of+Compatibility) 与
[dotnet/runtime](https://github.com/dotnet/runtime/blob/main/docs/coding-guidelines/breaking-change-definitions.md)
的划分：

- **源码兼容（source）**——你的代码还能不能通过编译。移除类型、改方法签名、
  给接口加抽象方法都会破坏它。
- **二进制兼容（binary）**——你**已经编译好**的 JAR 还能不能在新框架上加载和运行。
  破坏它的典型表现是 `NoSuchMethodError` / `NoClassDefFoundError`。
- **行为兼容（behavioral）**——编译过了、也加载起来了，但**做的事情变了**。

前两类由上面的移除清单和版本号规则管。本节管第三类。

### 哪些行为变更可以在 MINOR 直接做

不需要迁移期：

- 修正明确违反文档的行为（文档说返回 `Optional.empty()`，实际抛了 NPE）。
- 收紧此前未定义的输入的处理方式（此前传 `null` 是未定义行为，现在明确抛 `IllegalArgumentException`）。
- 性能、内存占用、日志文案、异常消息文本的变化。
- 修复安全问题。这一类可能在 PATCH 就发生，恕不预告。

### 哪些需要迁移期

需要迁移期：

- 有文档记载的默认值翻转。
- 从静默降级改成失败（例如可选依赖缺失时，此前跳过、之后拒绝加载）。
- 返回值语义变化（此前返回空集合、之后返回 `null`，反之亦然）。
- 副作用的时机或线程变化（此前同步、之后异步）。

迁移期走两步：

- **版本 N**：保持旧行为，但在触发该路径时打**一次性** WARNING。
  警告文本必须写明具体的目标版本号和反馈 issue 链接，例如：

  ```
  [UltiTools] 模块 <name> 依赖了 X 的旧行为（<旧行为的一句话描述>）。
  该行为将在 6.4.0 改为 <新行为>。迁移方式见 <issue 链接>。
  本警告每次启动只打一次。
  ```

- **版本 N+1**：真正切换到新行为，并移除该警告。

这一节的写法参考了 [PEP 387](https://peps.python.org/pep-0387/)，
核心是同一条：**先让人知道自己踩在了哪块地板上，再抽走它。**

## 支持范围

| 项 | 值 |
|---|---|
| 服务端 | Paper（不支持 plain Spigot——代码全面使用 Adventure `Component`） |
| 构建 JDK | 21 |
| 字节码目标 | Java 8（`-source`/`-target`，非 `--release`） |
| `plugin.yml` 的 `api-version` | `1.19`（Bukkit API 层级，与上面两项无关） |
| 模块 `plugin.yml` 的 `api-version` | `620`（UltiTools API 层级，与 Bukkit 的同名字段无关） |

### 运行时依赖来自哪里

框架 JAR 里只打包三个库：obliviate-invs（GUI）、XSeries（跨版本）、UniversalScheduler（调度）。
其余依赖一个都不在 JAR 里，而是走下面两条路之一：

| 投送方式 | 版本由谁决定 | 例子 |
|---|---|---|
| `plugin.yml` 的 `libraries:` 块，Paper 按坐标下载 | **本仓库** | Gson、MySQL Connector/J、HikariCP、Java-WebSocket、CGLIB |
| Paper 服务端自身携带 | **服主所装的 Paper 版本** | log4j、Paper 内部实现 `libraries:` 用的 Maven resolver 及其依赖 |

这条分界决定了第三方安全告警该由谁修。命中第一类的，在本仓库钉版本是有效的修复；
命中第二类的，唯一的修法是**升级 Paper** —— 在 `pom.xml` 里怎么写都不会改变服务器上
实际加载的那个 jar，只会制造「已经修了」的错觉。

注意 Maven 的 `provided` 作用域**不是**这条分界：上表两类依赖在 `pom.xml` 里都声明为
`provided`。判断依据是「有没有出现在 `libraries:` 块里」，不是作用域。

建议服主跟进 Paper 的构建更新，安全构建尤其如此。

## 反馈

对本策略有异议，或你的模块受到上述移除影响，
请在 [GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues) 提出。
