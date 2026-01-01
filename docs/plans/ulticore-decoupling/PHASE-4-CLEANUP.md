# Phase 4: 清理与删除

**状态**: ✅ 已完成  
**完成时间**: 2025-12-31  
**前置条件**: Phase 3 完成

---

## 目标

- 删除不再需要的 `SpigotVersionManager.java`
- 删除相关测试文件
- 清理不再需要的 env.yml 配置（可选）

---

## 任务清单

### 4.1 删除 SpigotVersionManager.java

**文件**: `src/main/java/com/ultikits/ultitools/manager/SpigotVersionManager.java`  
**操作**: 删除整个文件

```bash
rm src/main/java/com/ultikits/ultitools/manager/SpigotVersionManager.java
```

- [ ] 删除 SpigotVersionManager.java

---

### 4.2 删除 SpigotVersionManagerTest.java

**文件**: `src/test/java/com/ultikits/ultitools/manager/SpigotVersionManagerTest.java`  
**操作**: 删除整个文件

```bash
rm src/test/java/com/ultikits/ultitools/manager/SpigotVersionManagerTest.java
```

- [ ] 删除 SpigotVersionManagerTest.java

---

### 4.3 清理 env.yml 中的版本下载配置（可选）

**文件**: `src/main/resources/env.yml`  
**操作**: 可以移除与版本适配器下载相关的配置（如果存在）

检查是否有类似以下配置，评估是否删除：
```yaml
# 版本适配器相关（可以删除）
oss-url: "..."
versions-path: "/versions/"
```

- [ ] 评估并清理 env.yml（可选）

---

### 4.4 清理 versions 目录说明

运行时不再需要下载版本适配器 JAR，可以在文档中说明：

- 删除服务器 `plugins/UltiTools/versions/` 目录（如果存在）
- 该目录不再需要，插件现在内置跨版本支持

- [ ] 更新用户文档说明 versions 目录可删除（可选）

---

## 验证步骤

```bash
# 1. 验证文件已删除
ls src/main/java/com/ultikits/ultitools/manager/SpigotVersionManager.java 2>&1 | grep -q "No such file" && echo "✓ SpigotVersionManager.java 已删除"

ls src/test/java/com/ultikits/ultitools/manager/SpigotVersionManagerTest.java 2>&1 | grep -q "No such file" && echo "✓ SpigotVersionManagerTest.java 已删除"

# 2. 验证编译
mvn compile -Dmaven.test.skip=true

# 3. 验证打包
mvn package -DskipTests
```

- [ ] SpigotVersionManager.java 已删除
- [ ] SpigotVersionManagerTest.java 已删除
- [ ] 编译通过
- [ ] 打包成功

---

## 完成标准

- [x] SpigotVersionManager.java 已删除
- [x] 相关测试文件已删除
- [x] 无编译错误
- [x] `mvn package` 成功

---

## 下一步

完成后继续 [Phase 5: 测试与验证](PHASE-5-TESTING.md)

