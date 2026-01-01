# UltiTools-API 文档索引

> UltiTools-Reborn 项目文档中心

---

## 📚 文档目录

### 版本更新

| 文档 | 描述 |
|------|------|
| [CHANGELOG.md](./CHANGELOG.md) | 版本更新日志 |

### 迁移指南

| 文档 | 描述 |
|------|------|
| [API_MIGRATION_6.2.0.md](./wiki/migration/API_MIGRATION_6.2.0.md) | 从 6.1.x 迁移到 6.2.0 的详细指南 |

### 架构设计

| 文档 | 描述 |
|------|------|
| [ARCHITECTURE_REFACTORING.md](./wiki/ARCHITECTURE_REFACTORING.md) | 架构重构设计理念和模式 |

### 测试

| 文档 | 描述 |
|------|------|
| [TEST_COVERAGE_REPORT.md](./reports/TEST_COVERAGE_REPORT.md) | 单元测试覆盖率报告 |

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
