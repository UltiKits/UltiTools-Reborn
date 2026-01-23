# UltiMail 单元测试

## 测试概览

UltiMail 插件包含全面的单元测试，覆盖实体、配置、服务、GUI、监听器和命令组件。

### 测试统计

| 类别 | 测试类 | 测试数 | 状态 |
|------|--------|--------|------|
| 实体 | `MailDataTest` | 16 | ✅ 通过 |
| 配置 | `MailConfigTest` | 29 | ✅ 通过 |
| 服务 | `MailServiceTest` | 11 | ⏸️ 跳过* |
| GUI | `MailboxGUITest` | 3 | ⏸️ 跳过* |
| GUI | `SentboxGUITest` | 3 | ⏸️ 跳过* |
| GUI | `AttachmentSelectorPageTest` | 5 | ⏸️ 跳过* |
| 监听器 | `MailNotifyListenerTest` | 5 | ⏸️ 跳过* |
| 命令 | `MailCommandTest` | 8 | ⏸️ 跳过* |
| 命令 | `SendMailCommandTest` | 7 | ⏸️ 跳过* |

## 总计: 87 个测试 (45 通过, 42 跳过)

> *注: 需要 MockBukkit 的测试由于 Java 21 + Paper API 1.19 兼容性问题暂时跳过。

## 运行测试

```bash
# 运行所有测试
mvn test

# 只运行通过的测试
mvn test -Dtest=MailDataTest,MailConfigTest

# 运行单个测试类
mvn test -Dtest=MailDataTest
```

## 测试框架

- **JUnit 5** (5.10.1) - 测试框架
- **MockBukkit** (3.1.0) - Bukkit API 模拟 (受限制)
- **Mockito** (5.5.0) - 通用模拟
- **AssertJ** (3.24.2) - 流畅断言

## 测试结构

```
src/test/java/com/ultikits/plugins/mail/
├── utils/
│   ├── MockBukkitHelper.java    # MockBukkit 清理工具
│   └── TestHelper.java          # Mock 实例创建助手
├── entity/
│   └── MailDataTest.java        # 邮件数据实体测试
├── config/
│   └── MailConfigTest.java      # 配置实体测试
├── service/
│   └── MailServiceTest.java     # 邮件服务测试 [需 MockBukkit]
├── gui/
│   ├── MailboxGUITest.java      # 收件箱 GUI 测试 [需 MockBukkit]
│   ├── SentboxGUITest.java      # 发件箱 GUI 测试 [需 MockBukkit]
│   └── AttachmentSelectorPageTest.java  # 附件选择页测试 [需 MockBukkit]
├── listener/
│   └── MailNotifyListenerTest.java   # 通知监听器测试 [需 MockBukkit]
└── commands/
    ├── MailCommandTest.java     # /mail 命令测试 [需 MockBukkit]
    └── SendMailCommandTest.java # /sendmail 命令测试 [需 MockBukkit]
```

## 测试覆盖范围

### MailDataTest (16 个测试)
- 构造函数默认值
- `hasItems()` 方法
- `hasCommands()` 方法
- Getter/Setter 方法
- equals/hashCode 合约

### MailConfigTest (29 个测试)

- 默认配置值
- Getter/Setter 方法
- 边界值测试
- 消息占位符测试
- 邮件 SMTP 配置测试

## MockBukkit 兼容性问题

由于以下问题，部分测试暂时被 `@Disabled`:

1. **Java 21 兼容性**: MockBukkit 3.1.0 在 Java 21 环境下可能无法正确实例化插件
2. **Paper API 版本**: Paper API 1.19 与 MockBukkit 存在类加载器冲突
3. **PluginClassLoader 要求**: Bukkit `JavaPlugin` 需要特殊的类加载器

### 解决方案（待实施）

1. 升级 MockBukkit 版本（如果有更新的兼容版本）
2. 使用纯 Mockito 重写需要 Bukkit 环境的测试
3. 等待 MockBukkit 的 Java 21 支持更新

## 贡献指南

添加新测试时请遵循以下原则：

1. 使用 `@DisplayName` 提供中文测试描述
2. 使用 `@Nested` 类组织相关测试
3. 避免使用 MockBukkit 除非绝对必要
4. 优先使用纯 Mockito 进行模拟
5. 使用 AssertJ 的流畅断言风格
