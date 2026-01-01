# UltiTools-API 文档索引

> UltiTools-Reborn 项目文档中心

---

## 📚 文档目录

### 版本更新

| 文档 | 描述 |
|------|------|
| [CHANGELOG-6.2.0.md](./CHANGELOG-6.2.0.md) | 6.2.0 版本完整更新日志 |

### 迁移指南

| 文档 | 描述 |
|------|------|
| [API_MIGRATION_6.2.0.md](./API_MIGRATION_6.2.0.md) | 从 6.1.x 迁移到 6.2.0 的详细指南 |

### 架构设计

| 文档 | 描述 |
|------|------|
| [ARCHITECTURE_REFACTORING_GUIDE.md](./ARCHITECTURE_REFACTORING_GUIDE.md) | 架构重构设计理念和模式 |

### 测试

| 文档 | 描述 |
|------|------|
| [TEST_COVERAGE_REPORT.md](./TEST_COVERAGE_REPORT.md) | 单元测试覆盖率报告 |

---

## 🏗️ 6.2.0 重构概览

### 新增功能

- ✨ `@AsyncCommand` 异步命令注解
- ✨ 命令验证链 (责任链模式)
- ✨ 泛型数据实体 + 生命周期钩子
- ✨ GUI 模板方法模式
- ✨ 确认对话框 Builder
- ✨ 新类型解析器 (World, Location, Enchantment, GameMode)

### 设计模式

| 模式 | 应用 |
|------|------|
| Chain of Responsibility | 命令验证器 |
| Strategy | 类型解析器 |
| Template Method | GUI 页面 |
| Builder | 确认对话框 |
| Context Object | 命令上下文 |

### 废弃 API

| 废弃 | 替代 | 移除版本 |
|------|------|----------|
| `AbstractCommandExecutor` | `BaseCommandExecutor` | 7.0.0 |
| `AbstractDataEntity` | `BaseDataEntity<ID>` | 7.0.0 |
| `PagingPage` | `BasePaginationPage` | 7.0.0 |
| `OkCancelPage` | `BaseConfirmationPage` | 7.0.0 |

---

## 📁 项目结构

```
UltiTools-Reborn/
├── docs/                           # 📚 文档
│   ├── README.md                   # 本文件
│   ├── CHANGELOG-6.2.0.md          # 版本更新日志
│   ├── API_MIGRATION_6.2.0.md      # 迁移指南
│   ├── ARCHITECTURE_REFACTORING_GUIDE.md  # 架构设计
│   └── TEST_COVERAGE_REPORT.md     # 测试报告
├── src/
│   ├── main/java/                  # 源代码
│   │   └── com/ultikits/ultitools/
│   │       ├── abstracts/
│   │       │   ├── command/        # 🆕 命令系统
│   │       │   ├── data/           # 🆕 数据实体
│   │       │   └── gui/            # 🆕 GUI 系统
│   │       └── annotations/
│   │           └── command/        # 🆕 命令注解
│   └── test/java/                  # 测试代码
└── target/
    └── apidocs/                    # Javadoc
```

---

## 🔗 快速链接

### 开发者

- [命令系统迁移](./API_MIGRATION_6.2.0.md#命令系统迁移)
- [数据实体迁移](./API_MIGRATION_6.2.0.md#数据实体迁移)
- [GUI 系统迁移](./API_MIGRATION_6.2.0.md#gui-系统迁移)
- [自定义类型解析器](./API_MIGRATION_6.2.0.md#类型解析器使用)

### 测试

- [运行测试](./TEST_COVERAGE_REPORT.md#运行测试)
- [Mock 策略](./TEST_COVERAGE_REPORT.md#mock-策略)

### API 参考

- [Javadoc](../target/apidocs/index.html) (需要先运行 `mvn javadoc:javadoc`)

---

## 📞 联系方式

- GitHub: [UltiKits/UltiTools-Reborn](https://github.com/UltiKits/UltiTools-Reborn)
- Issues: [提交问题](https://github.com/UltiKits/UltiTools-Reborn/issues)
