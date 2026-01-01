# Phase 1: 添加依赖与配置

**状态**: ✅ 已完成  
**完成时间**: 2025-12-31  
**前置条件**: 无

---

## 目标

- 添加 XSeries Maven 依赖
- 配置 shade 插件进行包重定位
- 更新 plugin.yml 设置最低 API 版本

---

## 任务清单

### 1.1 添加 XSeries 依赖

**文件**: `pom.xml`  
**位置**: `<dependencies>` 节点内

```xml
<!-- XSeries for cross-version compatibility (1.8.8 - 1.21+) -->
<dependency>
    <groupId>com.github.cryptomorin</groupId>
    <artifactId>XSeries</artifactId>
    <version>13.0.0</version>
</dependency>
```

- [ ] 添加依赖到 pom.xml

---

### 1.2 配置 Shade Relocation

**文件**: `pom.xml`  
**位置**: `maven-shade-plugin` 的 `<relocations>` 节点内（如果没有 shade 插件，需要添加）

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.5.1</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <relocations>
                    <relocation>
                        <pattern>com.cryptomorin.xseries</pattern>
                        <shadedPattern>com.ultikits.libs.xseries</shadedPattern>
                    </relocation>
                </relocations>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <!-- 排除不需要的 XSeries 类以减小体积 -->
                            <exclude>com/cryptomorin/xseries/XBiome*</exclude>
                            <exclude>com/cryptomorin/xseries/NMSExtras*</exclude>
                            <exclude>com/cryptomorin/xseries/NoteBlockMusic*</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

- [ ] 添加或更新 maven-shade-plugin 配置

---

### 1.3 设置最低 API 版本

**文件**: `src/main/resources/plugin.yml`  
**添加行**:

```yaml
api-version: "1.13"
```

- [ ] 更新 plugin.yml

---

## 验证步骤

```bash
# 1. 验证依赖下载
mvn dependency:resolve

# 2. 验证编译
mvn compile

# 3. 验证打包
mvn package -DskipTests
```

- [ ] 依赖解析成功
- [ ] 编译通过
- [ ] 打包成功

---

## 完成标准

- [x] XSeries 依赖已添加
- [x] Shade relocation 已配置
- [x] plugin.yml 已更新
- [x] `mvn package` 成功

---

## 下一步

完成后继续 [Phase 2: 创建新工具类](PHASE-2-NEW-UTILITIES.md)

