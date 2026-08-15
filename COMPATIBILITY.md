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

### 本节只管框架自己的版本号

上面这套规则**只适用于 `UltiTools-API` 本身**。给你自己的模块定版本号是另一套契约，
判据是「服主换上新 JAR 之后需不需要动手」，见
[模块版本规范](https://dev.ultikits.com/zh/guide/advanced/module-versioning.html)
（[English](https://dev.ultikits.com/guide/advanced/module-versioning.html)）。

两者不一致是**故意的**，不要试图统一。差别在于版本号被拿去做什么：

- 框架的版本号会被**解析和链接**——Maven 拿它挑构件，已编译的下游插件在运行时链接它的类。
  这两件事问的都是兼容性问题。
- 模块的版本号也会被机器读，但**只用来排序**：`PluginManager.hasNewerVersionLoaded`
  和 `unregisterSupersededVersions` 在同一模块存在两个 JAR 时比较版本决定谁赢，
  `UpdateManager.checkModuleUpdates` 比较版本以报告有无更新。三者都走
  `VersionComparatorUtil.compare`，问的只是「A 是不是比 B 大」，**没有一个会去看
  这个差异属于 MAJOR、MINOR 还是 PATCH**。模块也不发布到 Maven、不被任何东西链接。

所以：模块版本号的**顺序**被机器消费，**MAJOR/MINOR/PATCH 的含义**不被机器消费，
后者是说给服主听的。框架这边两者都被机器消费，所以它的版本号没有同样的自由度。

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
| `manager.CommandManager.registerAll(UltiToolsPlugin, String)` | 6.2.5 | `registerAll(UltiToolsPlugin)` | 0 |
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
`6.2.1` 这个起算点是**刻意取的保守值**，不是因为 6.2.0 无从查证。6.2.0 确实发布到了
Maven Central、只是没有对应的 git 标签，但它在仓库历史里有发布提交
（`0286e26 release: UltiTools-API v6.2.0`），可以核对。把起算点定在更晚的 6.2.1 只会
延长废弃期、对下游更有利，所以保持不动。

**顺带记一条查证方法**：本项目的发布列表是 Maven Central 的 `maven-metadata.xml`，
**不是 `git tag`**——`git tag` 里没有 `v6.2.0`。用标签列表推断「某个版本从未发布」在
这个仓库会得出错误结论。

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

- **源码兼容（source）**——你的代码还能不能通过编译。移除类型、改方法的参数表、
  给接口加抽象方法都会破坏它。注意**改返回类型往往不破坏它**：调用方通常没写出返回
  类型的名字，重新编译一次就过去了。
- **二进制兼容（binary）**——你**已经编译好**的 JAR 还能不能在新框架上加载和运行。
  破坏它的典型表现是 `NoSuchMethodError` / `NoClassDefFoundError`。
  上一条里那种「重新编译就过去了」的改动，对不重新编译的 JAR 就是致命的。
- **行为兼容（behavioral）**——编译过了、也加载起来了，但**做的事情变了**。

移除清单和版本号规则管的是前两类里**有意为之**的那部分；无意打破二进制兼容的情况见
[移除清单覆盖不到的二进制不兼容](#移除清单覆盖不到的二进制不兼容)。本节管第三类。

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

## 移除清单覆盖不到的二进制不兼容

上面的移除清单只覆盖得了**有人知道自己在改 API** 的那些变更——它的两个前提
（带 `@Deprecated(forRemoval = true)`、跨过一个 MINOR）都要求改动者先意识到这是一次
API 变更。有一类改动不满足这个前提：它在作者眼里根本不是 API 变更，却改掉了公开方法的
**JVM 方法描述符**——于是它必定打破二进制兼容，却可能完全不打破源码兼容，从而绕过所有
以「有人会注意到」为前提的流程。

这已经发生过两次，都记在这里。第一次在 MINOR，第二次在 PATCH——**没有哪个版本级别是豁免的**：

### 第一次：6.1.1 → 6.2.0，一个 MINOR

移除 Spring 时，`UltiToolsPlugin` 里 context 字段的类型从
`AnnotationConfigApplicationContext` 换成了 `SimpleContainer`。该字段带 `@Getter`，
所以 Lombok 生成的 `getContext()` 的**返回类型**跟着变了：

```
针对 6.0.6 编译的模块，字节码里记的是
  getContext:()Lorg/springframework/context/annotation/AnnotationConfigApplicationContext;
6.2.0 及以后的框架提供的是
  getContext:()Lcom/ultikits/ultitools/context/SimpleContainer;
```

（起算点是 **6.2.0**，不是 6.2.1。6.2.0 发布到了 Maven Central 但仓库里没有对应的 git
tag——**发布列表看 `maven-metadata.xml`，不要看 `git tag`**。在 6.2.0 上排查同样会撞到
这个异常。）

返回类型是方法描述符的一部分，对 JVM 而言这是两个不同的方法，老 JAR 在 `registerSelf()`
里拿到 `NoSuchMethodError`。

**源码这一侧则取决于调用写法**，这正是它容易被漏掉的原因。UltiEconomy 那种
`getContext().getBean(X.class)` 没有写出返回类型的名字，同一份源码在两个版本上都编得过；
但只要源码写成 `AnnotationConfigApplicationContext ctx = plugin.getContext();`、把返回值
传给接收旧类型的方法、或者覆写 `getContext()`，重新编译就会失败——`SimpleContainer` 与
旧类型没有继承关系。所以这类改动的准确说法是「**必定破坏二进制兼容，是否破坏源码兼容
取决于调用方**」，不是「一律只破坏二进制兼容」。

三道防线因此同时失效：

- 没有任何东西被「移除」，移除清单里放不进这一条；
- 没有可以标 `@Deprecated` 的目标，`-Xlint:removal` 对下游一次都没响过；
- `PluginManager` 的版本门禁也拦不住——它只判 `api-version > 当前框架版本`，即
  「模块要求的框架比装的还新」这一个方向。老模块声明的下限确实被满足了，照样炸。

### 第二次：6.2.0 → 6.2.1，一个 PATCH

上面那次是 MINOR。第二次发生在 **PATCH** 里，所以「盯着 MINOR 就行」是不成立的。

`43f55ea refactor!: replace AbstractDataEntity with BaseDataEntity<String>` 改掉了
`DataOperator` **四个**方法的描述符——`insert(T)`、`update(T)`、`exist(T)`、`getById`：

```
6.2.0  insert   (Lcom/ultikits/ultitools/abstracts/AbstractDataEntity;)V
6.2.1  insert   (Lcom/ultikits/ultitools/abstracts/data/BaseDataEntity;)V
```

`AbstractDataEntity` 本身没被删，所以这一条同样进不了移除清单。

（`update(String, Object, Object)` 与 `exist(WhereCondition[])` 这两个重载不含实体类型，
描述符未变。同名方法里只有接收实体的那个重载受影响。）

**描述符变更本质上都是双向的**，两个实例都是——一个符号在两版里名字相同、描述符不同，
那么无论从哪一侧编译，另一侧都没有它。老 JAR 在新框架上炸（找 `(AbstractDataEntity)`，
已不存在），新 JAR 在老框架上也炸（找 `(BaseDataEntity)`，尚不存在）；同一份源码只改
pin 重编，两个产物各自只能跑在自己那一侧。第一个实例（`getContext()`）同理，只是当时
只有「老 JAR 撞新框架」这个方向被真实触发了。

**而第二个方向还多带一层：它是被放行之后才炸的。** 15 个官方模块统一把 `pom.xml` 的
pin 调到了 6.2.1，`plugin.yml` 的 `api-version` 却一个都没动，仍是 `620`。产物记的是
6.2.1 的描述符，声明的地板却是 6.2.0，而框架只看得到后者。结果：装了 6.2.0 的服务器
**加载成功**，然后在第一次数据读写时 `NoSuchMethodError`。11 个模块中招（剩下 4 个不碰
ORM，负向对照成立）。

Java 是惰性解析的，所以「装上去能起来」不构成证据——不碰数据路径的服主可以一直看不出问题。

**同一次提交里还有一个恰好相反的对照，值得一并记住。** 它把 `UltiToolsPlugin.getDataOperator`
的泛型上界从 `AbstractDataEntity` 换成了 `BaseDataEntity<String>`，但两版描述符
**完全相同**——上界被擦除，`T` 在描述符里早就是 `Class` / `DataOperator`：

```
6.2.0  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
6.2.1  getDataOperator  (Ljava/lang/Class;)Lcom/ultikits/ultitools/interfaces/DataOperator;
```

这是前面那种情况的镜像：**改泛型上界破坏源码兼容而不破坏二进制兼容；改返回类型或参数
类型破坏二进制兼容而不一定破坏源码兼容。** 两者都不涉及移除，所以两者都绕过移除清单。

**但这条只对 `getDataOperator` 这个调用点成立，不要推广到整个模块。** 拿到
`DataOperator` 之后你几乎一定会调 `insert` / `update` / `exist` / `getById`，而那四个
的描述符是变了的。所以一个用了 ORM 的模块，**两个方向都会断**：

| 构建时 pin | 跑在 6.2.0 | 跑在 6.2.1+ |
|---|---|---|
| 6.2.0 | ✅ | ❌ `NoSuchMethodError`（找 `(AbstractDataEntity)`，已不存在） |
| 6.2.1 | ❌ `NoSuchMethodError`（找 `(BaseDataEntity)`，尚不存在） | ✅ |

实测取的是同一个模块的同一份源码，只改 pin 重编：针对 6.2.0 编译的产物在 6.2.1 上缺 3 个
符号、在 6.2.0 上缺 0 个；针对 6.2.1 编译的产物反过来，在 6.2.0 上缺 3 个、在 6.2.1 上缺
0 个。**对称的，两侧都不通。** 「跑得动但编不过」只描述了 `getDataOperator` 那一行。

### 这对你意味着什么

**pin 得低不等于安全。** [模块版本规范](https://dev.ultikits.com/zh/guide/advanced/module-versioning)
里说「编译 against 旧 API 不会因为够到了更新的东西而 `NoSuchMethodError`」——那句话仍然
成立，但它只排除掉了**一个方向的原因**。反方向的原因（框架自己改了描述符）会给你同一个
异常。

**而且这一类没有免费的修法。** 光说「重新编译并重新发布」是不够的，甚至是错的：如果
`pom.xml` 里的 pin 还停在 6.0.6，重新跑一遍构建仍然照着 6.0.6 的 class 文件生成**旧的**
描述符，产物在新框架上照炸。要真正修好，必须**把编译依赖抬到含新描述符的那个版本再重编**。

**但抬 pin 只做完了一半，而且是不被检查的那一半。** 这里有两个互相独立的数字，别把它们
当成一个：

| 数字 | 决定什么 | 谁在检查 |
|---|---|---|
| `pom.xml` 里的 `UltiTools-API` 版本 | 你的字节码记录**哪一版的描述符** | 没有人。它是 `provided`，不进 JAR，框架运行时看不到它 |
| `plugin.yml` 的 `api-version` | 声明的运行时**下限** | `PluginManager.isUltiToolsVersionCompatible`，这是唯一被检查的值 |

所以「pin 就是地板」是错的：抬高 pin 不会抬高地板。一个针对新框架编译、却仍然声明旧
`api-version` 的构件，会被老服务器**放行**，然后在第一次调用新描述符时炸掉——同一个
`NoSuchMethodError`，方向反过来。**两个数字必须一起动。**

**这不是假想。** 上面第二个实例就是这么发生的：15 个官方模块把 pin 调到 6.2.1，
`api-version` 全部留在 `620`，其中 11 个的产物因此声明了一个比自己真实需求更低的地板
（2026-08-16 已全部修正为 `621`）。**没有任何工具报过警**——构建是绿的，插件在 6.2.1 以上
的服务器上一切正常，只有恰好停在 6.2.0 的服务器会先加载成功、再在第一次数据读写时炸。

想自查的话，判据是「**产物实际引用了哪些符号**」，不是「pom 里写了什么」。把你的模块 JAR
和你在 `api-version` 里声明的那一版框架 JAR 都解开，用 `javap -p -c` 导出模块引用的
`com/ultikits/ultitools/**` 方法与描述符，再逐条对照框架 JAR 里 `javap -p -s` 的输出。
现成的脚本见 [issue #284](https://github.com/UltiKits/UltiTools-Reborn/issues/284)。

**但对不上不等于「`api-version` 低了」，有两个成因，修法相反。** 看那条对不上的符号里写的
是哪一版的类型：

| 缺失符号引用的类型 | 说明什么 | 怎么修 |
|---|---|---|
| **新**的（如 `BaseDataEntity`） | 产物比声明的地板新 | **抬 `api-version`**，pin 不用动 |
| **旧**的（如 `AbstractDataEntity`） | 产物比声明的地板旧，pin 停在老版本没跟上 | **抬 pin 并重编**，抬 `api-version` 没用，反而更错 |

第二种正是本节这个实例的另一侧：一个 pin 停在 6.2.0、却声明 `api-version: 621` 的产物，
引用的是 `insert(AbstractDataEntity)`，而 6.2.1 里没有这个描述符——地板再抬也不会让它
出现。**先看缺的是哪一代符号，再决定动哪个数字。**

于是完整的修法是：抬 pin、重编、**把 `api-version` 一并抬到对应的 API 级别**，并接受
由此带来的后果。

不能横跨两侧的，准确说是**那个直接静态调用了该方法的构件**——描述符是编译期写进调用点
的，所以一个调用点只能对上一侧。据此有三条路，成本递增：

1. **接受地板抬高**（默认选这条）。老服务器继续留在旧 JAR 上，新 JAR 只服务新框架。
2. **按框架区间出不同构件**。要维护两条发布线。
3. **写一层兼容垫片**：用反射调用（`getMethod("getContext").invoke(plugin)` 拿到
   `Object`，再反射调 `getBean`），或者按框架版本惰性加载不同的适配器实现。反射调用点
   不静态链接任何一版的返回类型，所以**同一个构件确实能同时跑在两侧**。代价是这段路径
   失去编译期检查、出错要到运行时才知道，且以后框架再改它你不会收到任何编译警告。

第 3 条真实可行，别因为前两条写在前面就以为它不存在；但它把一个编译期就能发现的问题
换成了一个运行期才暴露的问题。只有在**必须继续支持旧服务器**时才值得。

换句话说，本文别处说的「pin 落后是正常状态、不必因为落后本身去动它」在这一种情况下**不
适用**：那条讲的是没有理由时不要动 pin，而描述符变更正是一个理由。

至于多久要检查一次，取决于下面那条门禁有没有接线——**现在还没有，所以答案是「框架版本
号一变就重新验证，PATCH 也算」**：

- 本文上面写着 PATCH「不移除公开 API」。那句承诺覆盖的是**有意为之**的移除，因为它靠的
  是人先意识到自己在改 API。无意的描述符变更按定义不在任何排期里，**所以它同样可能出现
  在一个 PATCH 里**。这句话初写时只是推论，本节的第二个实例（6.2.0 → 6.2.1）已经证实了
  它：那是一个 PATCH。**「盯着 MINOR 就够了」是不成立的。**
- japicmp 门禁接线之后，PATCH 的二进制兼容才是被机器逐方法验证过的。到那时才可以只在
  跨 MINOR 时操心这件事。

### 这对我们意味着什么

人工流程挡不住这一类——它要求作者在改一个字段类型时就想到「这会改掉一个 public 方法的
描述符」。挡得住的只有机器逐方法比对描述符，也就是 japicmp 门禁（issue #216）。在它接线
之前，本文件对二进制兼容的承诺**仅限于有意为之的移除**；无意的描述符变更我们只能事后
记录，不能保证事前发现。

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
