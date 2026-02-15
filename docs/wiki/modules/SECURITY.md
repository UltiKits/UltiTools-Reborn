# 安全系统 - Security System

本文档详细介绍 UltiTools-API 的安全机制，包括类加载安全、代码执行防护和插件沙箱。

---

## 目录

- [概述](#概述)
- [SecurityPolicy 安全策略](#securitypolicy-安全策略)
- [ClassLoaderUtils 安全加载](#classloaderutils-安全加载)
- [插件管理器集成](#插件管理器集成)
- [运行时配置](#运行时配置)
- [威胁防护](#威胁防护)

---

## 概述

UltiTools 框架实现了多层安全机制，防止恶意代码执行和资源滥用。安全系统基于以下原则：

- **纵深防御**: 多层验证（格式、策略、层级）
- **白名单优先**: 只允许明确信任的代码
- **快速失败**: 发现违规立即抛出异常
- **透明监控**: 所有违规都记录日志

**核心组件**:

- `SecurityPolicy` - 安全策略配置和验证
- `ClassLoaderUtils` - 安全类加载工具
- `PluginManager` - 插件加载安全集成

---

## SecurityPolicy 安全策略

`SecurityPolicy` 是核心安全策略类，提供类加载和反射操作的安全验证。

### 危险类黑名单

系统预定义了 19 个危险类，禁止加载：

| 类别 | 类名 | 威胁 |
|------|------|------|
| 进程执行 | `java.lang.ProcessBuilder` | 任意命令执行 |
| 运行时 | `java.lang.Runtime` | 代码执行 |
| 系统操作 | `java.lang.System` | 系统级操作 |
| 反射 | `java.lang.reflect.Method` | 反射执行 |
| 文件 I/O | `java.io.FileOutputStream` | 文件写入 |
| 文件 I/O | `java.io.FileInputStream` | 文件读取 |
| 文件 I/O | `java.io.RandomAccessFile` | 文件操作 |
| NIO | `java.nio.file.Files` | 文件操作 |
| NIO | `java.nio.file.Paths` | 路径操作 |
| 脚本 | `javax.script.ScriptEngine` | 脚本执行 |
| 脚本 | `javax.script.ScriptEngineManager` | 脚本管理 |
| 不安全 | `sun.misc.Unsafe` | 内存操作 |
| 不安全 | `jdk.internal.misc.Unsafe` | 内存操作 |
| 网络 | `java.net.Socket` | 网络连接 |
| 网络 | `java.net.ServerSocket` | 服务器套接字 |
| 网络 | `java.net.URL` | URL 访问 |
| 网络 | `java.net.URLConnection` | 网络连接 |
| 安全 | `java.security.AccessController` | 安全控制 |
| 类加载 | `java.lang.ClassLoader` | 类加载器操作 |

### 危险包前缀

禁止加载以下包前缀的任何类（9 个类别）：

```
java.lang.reflect   - 反射框架
java.security       - 安全框架
sun.misc            - Sun 内部工具
jdk.internal        - JDK 内部类
com.sun             - Sun 专有 API
javax.script        - 脚本引擎
java.rmi            - 远程方法调用
java.beans          - Beans 框架
javax.management    - JMX 管理
```

### 可疑关键字检测

即使在信任包中，包含以下关键字的类名也会被拒绝（16 个关键字）：

```
process, runtime, script, unsafe, file, network,
socket, classloader, reflection, invoke, exec,
shell, cmd, bash, powershell, system, native
```

### 信任包白名单

以下包前缀被视为可信任（6 个类别）：

```java
com.ultikits.ultitools  - 框架核心类
org.bukkit              - Bukkit API
net.md_5.bungee         - BungeeCord API
io.papermc.paper        - Paper API
org.spigotmc            - Spigot
net.kyori.adventure     - Adventure 文本 API
```

### 验证方法

**`isSafeClassName(String className)`** - 四级验证：

```java
// 验证流程
1. 空值检查
2. 系统危险类黑名单验证
3. 危险包前缀验证
4. 信任包白名单验证
5. 可疑关键字检测

// 使用示例
if (SecurityPolicy.isSafeClassName("com.ultikits.ultitools.MyService")) {
    // 安全，可以加载
}

if (!SecurityPolicy.isSafeClassName("java.lang.ProcessBuilder")) {
    // 不安全，拒绝加载
}
```

**`isSafeParameterType(Class<?> clazz)`** - 参数类型安全：

```java
// 允许的类型
- 基本类型: int, long, double, float, boolean, char, byte, short
- 包装类型: Integer, Long, Double, Float, Boolean, Character, Byte, Short
- 常用类型: String, List, Map, Set, ArrayList, HashMap, HashSet
- 信任包中的类

// 使用示例
if (SecurityPolicy.isSafeParameterType(String.class)) {
    // 安全
}
```

**`isSafeFileStructure(long fileSize, int entryCount)`** - 文件结构验证：

```java
// 限制
- 文件大小上限: 100MB
- 条目数量上限: 10,000

// 使用示例
if (SecurityPolicy.isSafeFileStructure(jarFile.length(), entryCount)) {
    // 安全，可以解压
}
```

---

## ClassLoaderUtils 安全加载

`ClassLoaderUtils` 提供安全的类加载工具方法。

### 类名格式验证

使用正则表达式验证类名格式：

```
^[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*$
```

验证内容：

- 有效的 Java 标识符语法
- 正确的包命名规范
- 防止通过无效字符注入
- 拒绝：双点号、数字开头、连字符、空格、特殊字符

### 核心方法

**`loadClass(String className)`** - 基本安全加载：

```java
try {
    Class<?> clazz = ClassLoaderUtils.loadClass("com.example.MyService");
} catch (SecurityException e) {
    // 安全策略违规
} catch (ClassNotFoundException e) {
    // 类不存在
}
```

**`loadClass(String className, boolean initialize)`** - 控制初始化：

```java
// 延迟初始化
Class<?> clazz = ClassLoaderUtils.loadClass("com.example.MyService", false);
```

**`loadPluginClass(String className)`** - 插件类加载：

```java
// 强制验证插件包前缀和类层级
Class<?> pluginClass = ClassLoaderUtils.loadPluginClass(
    "com.ultikits.plugins.myplugin.MyPlugin"
);
// 必须以 com.ultikits.* 开头
// 必须继承 UltiToolsPlugin
```

**`validateClassLoaderHierarchy(ClassLoader classLoader)`** - 层级验证：

```java
// 验证类加载器链
if (ClassLoaderUtils.validateClassLoaderHierarchy(customLoader)) {
    // 类加载器层级安全
}
```

---

## 插件管理器集成

`PluginManager` 在加载插件时使用安全系统。

### JAR 文件验证

```java
// 在加载前验证 JAR 文件结构
if (!SecurityPolicy.isSafeFileStructure(jarFile.length(), entryCount)) {
    logger.warning("[UltiTools-Security] JAR 文件结构不安全: " + jarPath);
    return false;
}
```

### 插件类加载

```java
try {
    Class<?> pluginClass = ClassLoaderUtils.loadPluginClass(className);
    // 成功加载
} catch (SecurityException e) {
    logger.warning("[UltiTools-Security] 安全策略违规: " + e.getMessage());
    // 继续扫描其他类
}
```

### 扫描限制

```java
// 每个 JAR 最多扫描 1000 个类（DoS 防护）
int maxClasses = 1000;
```

### 参数类型验证

```java
// 依赖注入时验证参数类型
if (SecurityPolicy.isSafeParameterType(parameterClass)) {
    // 允许注入
}
```

---

## 运行时配置

### 添加信任包

```java
// 添加第三方库信任包
SecurityPolicy.addTrustedPackage("com.example.trusted.plugin");

// 日志输出
// [INFO] [UltiTools-Security] 已添加信任包: com.example.trusted.plugin
```

### 添加危险类

```java
// 添加额外的危险类到黑名单
SecurityPolicy.addDangerousClass("com.malicious.Evil");

// 日志输出
// [INFO] [UltiTools-Security] 已添加危险类: com.malicious.Evil
```

### 获取安全摘要

```java
String summary = SecurityPolicy.getSecurityPolicySummary();
// 返回当前安全策略配置信息
```

---

## 威胁防护

### 威胁防护矩阵

| 威胁类型 | 防护机制 |
|----------|----------|
| **代码执行** | ProcessBuilder、Runtime、System 类黑名单 |
| **脚本注入** | ScriptEngine、ScriptEngineManager 黑名单 |
| **反射滥用** | java.lang.reflect.* 包黑名单 |
| **文件系统访问** | 文件 I/O 类（FileOutputStream 等）黑名单 |
| **网络访问** | Socket、URL、URLConnection 类黑名单 |
| **不安全操作** | sun.misc.Unsafe、jdk.internal.misc.Unsafe 黑名单 |
| **远程调用** | java.rmi.* 包黑名单 |
| **Zip 炸弹** | 100MB 文件大小限制、10,000 条目限制 |
| **类加载器逃逸** | ClassLoader 操作黑名单 |
| **反射注入** | 参数类型安全验证 |
| **无效类名** | 正则表达式格式验证 |
| **DoS 攻击** | 每个 JAR 1000 类扫描限制 |
| **不信任包** | 严格的包白名单 |
| **可疑类名** | 关键字检测 |

### 安全日志

所有安全违规都会记录到日志：

```
[WARNING] [UltiTools-Security] 违规: 危险类 - java.lang.ProcessBuilder
[WARNING] [UltiTools-Security] 违规: 危险包 - java.lang.reflect.Method
[WARNING] [UltiTools-Security] 违规: 可疑关键字 - com.example.ProcessExecutor
```

---

## 设计原则

1. **纵深防御**: 多层验证（格式、策略、层级）
2. **白名单优先**: 只有明确信任的代码才被允许
3. **快速失败**: 违规时立即抛出异常
4. **默认不可变**: 核心黑名单/白名单是静态的
5. **可扩展运行时**: 可动态添加自定义包/类
6. **透明监控**: 所有违规记录日志供审计
7. **Bukkit 集成**: Bukkit API 被视为信任框架
8. **零信任**: 不存在隐式信任，所有代码必须通过验证

---

## 最佳实践

### 插件开发者

1. **使用信任包前缀**
   ```java
   // 推荐
   package com.ultikits.plugins.myplugin;

   // 不推荐
   package com.random.package;
   ```

2. **避免使用反射**
   - 使用框架提供的依赖注入
   - 使用框架提供的配置系统

3. **避免可疑类名**
   ```java
   // 避免
   public class ProcessExecutor { }
   public class ScriptRunner { }

   // 推荐
   public class TaskHandler { }
   public class CommandProcessor { }
   ```

### 服务器管理员

1. **检查安全日志**
   - 定期查看 `[UltiTools-Security]` 日志
   - 关注被拒绝的类加载尝试

2. **验证第三方插件**
   - 只安装来自可信来源的插件
   - 检查插件是否有异常的包结构

3. **运行时配置**
   - 谨慎使用 `addTrustedPackage()`
   - 只在必要时添加信任包

---

## 测试覆盖

安全系统有完整的测试覆盖：

- `SecurityPolicyTest` - 11 个测试组
  - 危险类检测
  - 危险包检测
  - 关键字检测
  - 文件结构验证
  - 运行时配置

- `ClassLoaderUtilsTest` - 14 个测试组
  - 信任类加载
  - 危险类阻止
  - 格式验证
  - 类加载器层级

- `ClassLoaderSecurityTest` - 安全专项测试
  - 恶意类加载阻止
  - 参数类型安全
  - 文件结构验证

---

> **下一步**: 阅读 [数据存储](./DATA_STORAGE.md) 了解框架的 ORM 系统
