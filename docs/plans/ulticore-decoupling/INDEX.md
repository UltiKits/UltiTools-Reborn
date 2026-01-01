# UltiTools 迁移计划目录

## 项目：脱离 UltiCore-Core 依赖

**目标**: 使用 XSeries 库替代自定义的 VersionWrapper 动态加载机制，实现零维护成本的跨版本兼容。

**最低支持版本**: 1.13+

---

## 计划进度

| 阶段 | 名称 | 状态 | 文件 |
|------|------|------|------|
| Phase 1 | 添加依赖与配置 | ✅ 已完成 | [PHASE-1-DEPENDENCIES.md](PHASE-1-DEPENDENCIES.md) |
| Phase 2 | 创建新工具类 | ✅ 已完成 | [PHASE-2-NEW-UTILITIES.md](PHASE-2-NEW-UTILITIES.md) |
| Phase 3 | 重构现有代码 | ✅ 已完成 | [PHASE-3-REFACTORING.md](PHASE-3-REFACTORING.md) |
| Phase 4 | 清理与删除 | ✅ 已完成 | [PHASE-4-CLEANUP.md](PHASE-4-CLEANUP.md) |
| Phase 5 | 测试与验证 | ✅ 已完成 | [PHASE-5-TESTING.md](PHASE-5-TESTING.md) |

**状态说明**: ⬜ 未开始 | 🔄 进行中 | ✅ 已完成

> 🎉 **迁移已完成!** 所有 2648 个测试通过，JAR 打包成功 (391KB)

---

## 预期收益

| 指标 | 迁移前 | 迁移后 |
|------|--------|--------|
| JAR 体积 | 328KB + 运行时下载 | ~500KB (含 XSeries) |
| 网络依赖 | 需要 CDN | 完全离线可用 |
| 维护成本 | 每版本需新增适配器 | 零 (XSeries 社区维护) |
| 代码量 | VersionWrapper + 动态加载 | ~80 行 VersionUtils |

---

## 快速开始

1. 阅读 [Phase 1](PHASE-1-DEPENDENCIES.md) 开始迁移
2. 完成每个阶段后，更新本文件中的状态
3. 运行测试验证迁移结果

---

## 变更日志

| 日期 | 阶段 | 描述 |
|------|------|------|
| 2025-12-31 | - | 创建迁移计划 |
| 2026-01-01 | Phase 1 | 添加 XSeries 13.0.0 依赖 |
| 2026-01-01 | Phase 2 | 创建 XVersionUtils.java, DefaultVersionWrapper.java |
| 2026-01-01 | Phase 3 | 重构 10+ 文件使用 XVersionUtils |
| 2026-01-01 | Phase 4 | 删除 SpigotVersionManager |
| 2026-01-01 | Phase 5 | 测试通过 (2648 tests)，打包成功 |

