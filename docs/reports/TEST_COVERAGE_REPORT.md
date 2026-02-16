# UltiTools-API 6.2.0 测试覆盖报告

> 最后更新: 2025年1月  
> 测试框架: JUnit 5 + Mockito

---

## 测试统计

```
===============================================
Tests run: 2655, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
===============================================
```

---

## 模块测试覆盖

### 1. 命令系统 (`abstracts.command`)

#### BaseCommandExecutor 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `BaseCommandExecutorTest` | 12 | 命令执行器核心逻辑 |

**测试场景:**

- ✅ 默认构造函数创建
- ✅ 自定义验证链创建
- ✅ 命令映射扫描
- ✅ 方法匹配算法
- ✅ 参数解析（简单/可变参数）
- ✅ Help 命令处理
- ✅ Tab 补全建议
- ✅ 匹配分数计算

#### CommandContext 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `CommandContextTest` | 10 | 上下文不可变性 |

**测试场景:**

- ✅ Builder 模式创建
- ✅ 玩家发送者检测
- ✅ 非玩家发送者检测
- ✅ 方法匹配更新 (withMatchedMethod)
- ✅ 参数解析更新 (withParsedParams)
- ✅ 空参数处理
- ✅ 不可变性验证

#### ValidationChain 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `ValidationChainTest` | 10 | 验证链执行 |

**测试场景:**

- ✅ SenderTypeValidator - PLAYER/CONSOLE/BOTH 类型
- ✅ PermissionValidator - 权限检查
- ✅ PermissionValidator - OP 检查
- ✅ 验证链顺序执行
- ✅ 空链通过
- ✅ ValidationResult 状态

---

### 2. 类型解析器 (`abstracts.command.parser`)

#### TypeParserRegistry 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `TypeParserRegistryTest` | 12 | 解析器注册表 |

**测试场景:**

- ✅ 单例模式一致性
- ✅ String 解析
- ✅ Integer 解析（正常/异常）
- ✅ Double 解析
- ✅ Boolean 解析
- ✅ Long 解析
- ✅ Float 解析
- ✅ 解析器存在性检查
- ✅ 获取所有解析器
- ✅ 数组解析

#### LocationParser 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `LocationParserTest` | 18 | Location 解析 |

**测试场景:**

- ✅ 返回类型检查
- ✅ x,y,z 格式解析
- ✅ 小数坐标解析
- ✅ 负坐标解析
- ✅ 无默认世界异常
- ✅ world,x,y,z 格式解析
- ✅ 无效世界名异常
- ✅ world,x,y,z,yaw,pitch 格式解析
- ✅ 负旋转值解析
- ✅ 无效格式异常
- ✅ 非数字值异常
- ✅ 参数不足异常
- ✅ 5 部分参数异常
- ✅ 数组解析
- ✅ 零坐标处理
- ✅ 大坐标处理
- ✅ 空字符串/null 异常

#### WorldParser 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `WorldParserTest` | 12 | World 解析 |

**测试场景:**

- ✅ 返回类型检查
- ✅ 有效世界名解析
- ✅ 无效世界名异常
- ✅ Nether 世界解析
- ✅ End 世界解析
- ✅ 空字符串异常
- ✅ null 异常
- ✅ 数组解析
- ✅ 数组包含无效世界异常
- ✅ 特殊字符世界名
- ✅ 下划线世界名
- ✅ 数字世界名

#### EnchantmentParser 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `EnchantmentParserTest` | 12 | Enchantment 解析 |

**测试场景:**

- ✅ 返回类型检查
- ✅ 精确名称解析
- ✅ 小写名称解析
- ✅ 混合大小写解析
- ✅ 部分匹配解析
- ✅ 小写部分匹配
- ✅ 不存在附魔异常
- ✅ 空字符串异常
- ✅ null 异常
- ✅ 数组解析

#### GameModeParser 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `GameModeParserTest` | 16 | GameMode 解析 |

**测试场景:**

- ✅ 返回类型检查
- ✅ SURVIVAL 名称解析（大小写不敏感）
- ✅ CREATIVE 名称解析
- ✅ ADVENTURE 名称解析
- ✅ SPECTATOR 名称解析
- ✅ 数字 0 = SURVIVAL
- ✅ 数字 1 = CREATIVE
- ✅ 数字 2 = ADVENTURE
- ✅ 数字 3 = SPECTATOR
- ✅ 无效数字异常
- ✅ 无效名称异常
- ✅ 空值异常
- ✅ null 异常
- ✅ 数组解析

---

### 3. 数据实体 (`abstracts.data`)

#### DataEntity 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `DataEntityTest` | 12 | 数据实体生命周期 |

**测试场景:**

**BaseDataEntity:**

- ✅ UUID ID 创建
- ✅ Long ID 创建
- ✅ onCreate 钩子调用
- ✅ onUpdate 钩子调用
- ✅ onDelete 钩子调用
- ✅ onLoad 钩子调用
- ✅ validate 通过
- ✅ validate 失败
- ✅ isNew 状态追踪

**AuditableDataEntity:**

- ✅ onCreate 设置审计字段
- ✅ onUpdate 设置审计字段
- ✅ 无当前用户时使用 null

---

### 4. GUI 系统 (`abstracts.gui`)

#### BaseInventoryPage 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `BaseInventoryPageTest` | 11 | GUI 基类 |

**测试场景:**

- ✅ 正确的页面大小（3行/6行）
- ✅ 默认显示底部工具栏
- ✅ 禁用底部工具栏
- ✅ 方法链式调用
- ✅ 底部中心槽位计算（3行/6行）
- ✅ 从末尾计算槽位
- ✅ 带工具栏的内容槽位
- ✅ 无工具栏的内容槽位

#### BasePaginationPage 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `BasePaginationPageTest` | 6 | 分页页面 |

**测试场景:**

- ✅ 正确的页面大小
- ✅ 按钮位置常量
- ✅ 分页管理器初始化
- ✅ 初始页面为 1
- ✅ 空项目至少 1 页

#### BaseConfirmationPage 测试

| 测试类 | 测试数 | 覆盖内容 |
|--------|--------|----------|
| `BaseConfirmationPageTest` | 14 | 确认对话框 |

**测试场景:**

- ✅ 正确的页面大小
- ✅ 取消按钮位置 (column 3)
- ✅ OK 按钮位置 (column 5)
- ✅ 默认 OK 按钮名称
- ✅ 默认取消按钮名称
- ✅ Builder 创建
- ✅ 自定义 ID
- ✅ 自定义标题
- ✅ 自定义行数
- ✅ 方法链式调用
- ✅ onConfirm 回调
- ✅ onCancel 回调
- ✅ content 设置
- ✅ 自定义按钮名称
- ✅ null 时回退默认名称

---

## Mock 策略

### 常用 Mock 设置

```java
@ExtendWith(MockitoExtension.class)
class MyTest {
    
    @Mock private Player mockPlayer;
    @Mock private UltiTools mockUltiTools;
    @Mock private VersionWrapper mockVersionWrapper;
    
    private MockedStatic<UltiTools> ultiToolsMock;
    private MockedStatic<Bukkit> bukkitMock;
    
    @BeforeEach
    void setUp() {
        // 静态 Mock
        ultiToolsMock = mockStatic(UltiTools.class);
        ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        
        // 实例 Mock
        lenient().when(mockUltiTools.i18n(anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mockUltiTools.getVersionWrapper())
            .thenReturn(mockVersionWrapper);
    }
    
    @AfterEach
    void tearDown() {
        if (ultiToolsMock != null) ultiToolsMock.close();
        if (bukkitMock != null) bukkitMock.close();
    }
}
```

### Bukkit 静态方法 Mock

```java
bukkitMock = mockStatic(Bukkit.class);

// Mock getWorld
bukkitMock.when(() -> Bukkit.getWorld("world")).thenReturn(mockWorld);

// Mock getWorlds
bukkitMock.when(Bukkit::getWorlds)
    .thenReturn(Collections.singletonList(mockWorld));
```

### Enchantment 静态方法 Mock

```java
try (MockedStatic<Enchantment> enchantMock = mockStatic(Enchantment.class)) {
    enchantMock.when(() -> Enchantment.getByName("SHARPNESS"))
        .thenReturn(mockSharpness);
    enchantMock.when(Enchantment::values)
        .thenReturn(new Enchantment[]{mockSharpness, mockProtection});
    
    // 测试代码
}
```

---

## 测试文件列表

```
src/test/java/com/ultikits/ultitools/
├── abstracts/
│   ├── command/
│   │   ├── BaseCommandExecutorTest.java
│   │   ├── CommandContextTest.java
│   │   ├── parser/
│   │   │   ├── TypeParserRegistryTest.java
│   │   │   ├── LocationParserTest.java
│   │   │   ├── WorldParserTest.java
│   │   │   ├── EnchantmentParserTest.java
│   │   │   └── GameModeParserTest.java
│   │   └── validation/
│   │       └── ValidationChainTest.java
│   ├── data/
│   │   └── DataEntityTest.java
│   └── gui/
│       ├── BaseInventoryPageTest.java
│       ├── BasePaginationPageTest.java
│       └── BaseConfirmationPageTest.java
```

---

## 运行测试

### 运行所有测试

```bash
cd /home/wisdomme/Code-Folder/UltiTools-Reborn
mvn test
```

### 运行特定测试类

```bash
mvn test -Dtest=BaseCommandExecutorTest
```

### 运行特定测试方法

```bash
mvn test -Dtest=BaseCommandExecutorTest#shouldCreateWithDefaultConstructor
```

### 运行测试并生成报告

```bash
mvn test surefire-report:report
# 报告位置: target/surefire-reports/
```

---

## 已知跳过的测试

| 测试 | 原因 |
|------|------|
| 7 个测试被跳过 | 依赖服务器环境或需要真实 NMS |

---

## 测试改进建议

### 待添加测试

1. **集成测试** - 命令执行完整流程
2. **性能测试** - 大量数据分页
3. **并发测试** - 验证锁机制

### 覆盖率目标

| 模块 | 当前 | 目标 |
|------|------|------|
| command | ~85% | 90% |
| parser | ~95% | 95% |
| validation | ~90% | 95% |
| data | ~80% | 85% |
| gui | ~75% | 80% |
