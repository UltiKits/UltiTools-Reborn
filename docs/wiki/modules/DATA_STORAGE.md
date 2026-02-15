# 数据存储

本文档详细介绍 UltiTools-API 的 ORM 数据存储系统。

---

## 目录

- [概述](#概述)
- [支持的存储类型](#支持的存储类型)
- [数据实体定义](#数据实体定义)
- [DataOperator 使用](#dataoperator-使用)
- [查询操作](#查询操作)
- [高级特性](#高级特性)
- [最佳实践](#最佳实践)

---

## 概述

UltiTools 提供统一的数据访问抽象层，让开发者无需关心底层存储实现：

- **统一 API**: `DataOperator<T>` 接口
- **多存储支持**: MySQL、SQLite、JSON
- **ORM 映射**: 使用 `@Table` 和 `@Column` 注解
- **用户可选**: 服务器管理员在配置中选择存储方式

```
┌─────────────────────────────────────────┐
│          Your Plugin Code               │
│   DataOperator<PlayerData> operator     │
└───────────────┬─────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────┐
│            DataStore Interface          │
└───────────────┬─────────────────────────┘
                │
    ┌───────────┼───────────┐
    ▼           ▼           ▼
┌───────┐  ┌────────┐  ┌────────┐
│ MySQL │  │ SQLite │  │  JSON  │
└───────┘  └────────┘  └────────┘
```

---

## 支持的存储类型

### MySQL

**适用场景**: 多服务器共享数据、高并发、生产环境

**配置** (`config.yml`):
```yaml
mysql:
  enable: true
  host: localhost
  port: 3306
  database: ultitools
  username: root
  password: password

datasource:
  type: mysql
```

### SQLite

**适用场景**: 单服务器、中等数据量、默认推荐

**配置**:
```yaml
datasource:
  type: sqlite
```

数据文件位于: `plugins/UltiTools/data/database.db`

### JSON

**适用场景**: 开发测试、小数据量、人工可读

**配置**:
```yaml
datasource:
  type: json
```

数据文件位于: `plugins/UltiTools/pluginConfig/<插件名>/data/<表名>/`

---

## 数据实体定义

### 基本实体

```java
import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Table("player_data")  // 表名/文件夹名
public class PlayerData extends AbstractDataEntity {
    
    @Column("uuid")
    private String uuid;
    
    @Column("name")
    private String name;
    
    @Column(value = "balance", type = "DOUBLE")
    private double balance;
    
    @Column(value = "last_login", type = "BIGINT")
    private long lastLogin;
    
    @Column("is_vip")
    private boolean vip;
}
```

### @Table 注解

指定数据表/文件夹名称：

```java
@Table("economy_accounts")  // MySQL/SQLite 表名，JSON 文件夹名
public class Account extends AbstractDataEntity {
    // ...
}
```

### @Column 注解

映射字段到数据列：

```java
@Column("column_name")  // 列名
private String field;

@Column(value = "amount", type = "DECIMAL(10,2)")  // 指定 SQL 类型
private BigDecimal amount;

@Column(value = "data", type = "TEXT")  // 长文本
private String jsonData;
```

**支持的 SQL 类型**:

| type 值 | 描述 |
|---------|------|
| `VARCHAR(255)` | 默认，字符串 |
| `INT` | 整数 |
| `BIGINT` | 长整数 |
| `DOUBLE` | 双精度浮点 |
| `FLOAT` | 单精度浮点 |
| `DECIMAL(p,s)` | 精确数值 |
| `TEXT` | 长文本 |
| `BOOLEAN` | 布尔值 |
| `TIMESTAMP` | 时间戳 |

---

## DataOperator 使用

### 获取 DataOperator

在 `UltiToolsPlugin` 子类中：

```java
public class MyPlugin extends UltiToolsPlugin {
    
    @Override
    public boolean registerSelf() {
        // 获取数据操作器
        DataOperator<PlayerData> operator = getDataOperator(PlayerData.class);
        
        // 使用操作器
        // ...
        
        return true;
    }
}
```

在 Service 中：

```java
@Service
public class PlayerService {
    
    private final DataOperator<PlayerData> dataOperator;
    
    public PlayerService(UltiToolsPlugin plugin) {
        this.dataOperator = plugin.getDataOperator(PlayerData.class);
    }
}
```

### 基本 CRUD 操作

#### 插入数据

```java
PlayerData player = new PlayerData();
player.setUuid(uuid.toString());
player.setName("Steve");
player.setBalance(100.0);
player.setLastLogin(System.currentTimeMillis());

dataOperator.insert(player);
```

#### 查询数据

```java
// 通过 ID 查询
PlayerData player = dataOperator.getById(1);

// 查询所有
List<PlayerData> allPlayers = dataOperator.getAll();

// 条件查询
List<PlayerData> vipPlayers = dataOperator.getAll(
    WhereCondition.builder()
        .column("is_vip")
        .value(true)
        .build()
);
```

#### 更新数据

```java
// 更新单个字段
dataOperator.update("balance", 200.0, playerId);

// 更新整个实体
player.setBalance(300.0);
player.setLastLogin(System.currentTimeMillis());
dataOperator.update(player);
```

#### 删除数据

```java
// 通过 ID 删除
dataOperator.delById(playerId);

// 条件删除
dataOperator.del(
    WhereCondition.builder()
        .column("balance")
        .value(0)
        .comparison(Comparison.LESS_THAN)
        .build()
);
```

---

## 查询操作

### WhereCondition 构建

```java
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.entities.Comparison;

// 等于
WhereCondition eq = WhereCondition.builder()
    .column("name")
    .value("Steve")
    .build();  // name = 'Steve'

// 大于
WhereCondition gt = WhereCondition.builder()
    .column("balance")
    .value(100)
    .comparison(Comparison.GREATER_THAN)
    .build();  // balance > 100

// 小于等于
WhereCondition lte = WhereCondition.builder()
    .column("level")
    .value(50)
    .comparison(Comparison.LESS_THAN_OR_EQUAL)
    .build();  // level <= 50

// 不等于
WhereCondition neq = WhereCondition.builder()
    .column("status")
    .value("banned")
    .comparison(Comparison.NOT_EQUAL)
    .build();  // status != 'banned'
```

### 多条件查询

```java
// AND 条件（多个 WhereCondition）
List<PlayerData> results = dataOperator.getAll(
    WhereCondition.builder().column("is_vip").value(true).build(),
    WhereCondition.builder().column("balance").value(1000).comparison(Comparison.GREATER_THAN).build()
);
// WHERE is_vip = true AND balance > 1000
```

### 模糊查询

```java
import cn.hutool.db.sql.Condition;

// 包含
List<PlayerData> results = dataOperator.getLike("name", "Steve", Condition.LikeType.Contains);
// name LIKE '%Steve%'

// 以...开头
List<PlayerData> results = dataOperator.getLike("name", "Ste", Condition.LikeType.StartWith);
// name LIKE 'Ste%'

// 以...结尾
List<PlayerData> results = dataOperator.getLike("name", "eve", Condition.LikeType.EndWith);
// name LIKE '%eve'
```

### 分页查询

```java
// 第 1 页，每页 10 条
List<PlayerData> page1 = dataOperator.page(1, 10);

// 带条件分页
List<PlayerData> vipPage = dataOperator.page(1, 10,
    WhereCondition.builder().column("is_vip").value(true).build()
);
```

### 存在性检查

```java
// 通过实体检查
PlayerData player = new PlayerData();
player.setUuid(uuid.toString());
boolean exists = dataOperator.exist(player);

// 通过条件检查
boolean hasRich = dataOperator.exist(
    WhereCondition.builder()
        .column("balance")
        .value(1000000)
        .comparison(Comparison.GREATER_THAN)
        .build()
);
```

---

## 高级特性

### 复杂实体关系

虽然 UltiTools ORM 不直接支持关联查询，但可以通过手动管理实现：

```java
@Data
@Table("player_homes")
public class PlayerHome extends AbstractDataEntity {
    
    @Column("player_uuid")
    private String playerUuid;
    
    @Column("home_name")
    private String homeName;
    
    @Column("world")
    private String world;
    
    @Column(value = "x", type = "DOUBLE")
    private double x;
    
    @Column(value = "y", type = "DOUBLE")
    private double y;
    
    @Column(value = "z", type = "DOUBLE")
    private double z;
}

// 查询玩家所有家
List<PlayerHome> homes = homeOperator.getAll(
    WhereCondition.builder()
        .column("player_uuid")
        .value(player.getUniqueId().toString())
        .build()
);
```

### JSON 存储结构

当使用 JSON 存储时，数据组织如下：

```
plugins/UltiTools/pluginConfig/<插件名>/data/
└── player_data/
    ├── 1.json
    ├── 2.json
    └── 3.json
```

每个 JSON 文件内容：

```json
{
  "id": 1,
  "uuid": "uuid-string",
  "name": "Steve",
  "balance": 100.0,
  "lastLogin": 1704067200000,
  "vip": false
}
```

### 事务支持

MySQL 和 SQLite 自动使用事务，JSON 存储不支持事务。

对于需要原子操作的场景：

```java
// MySQL/SQLite - 操作自动在事务中
try {
    dataOperator.update("balance", newBalance, player1Id);
    dataOperator.update("balance", newBalance2, player2Id);
} catch (Exception e) {
    // 发生错误时自动回滚
    logger.error("转账失败", e);
}
```

---

## 完整示例

### 玩家数据服务

```java
@Service
public class PlayerDataService {
    
    private final DataOperator<PlayerData> dataOperator;
    
    @Autowired
    public PlayerDataService(MyPlugin plugin) {
        this.dataOperator = plugin.getDataOperator(PlayerData.class);
    }
    
    /**
     * 获取或创建玩家数据
     */
    public PlayerData getOrCreate(Player player) {
        List<PlayerData> results = dataOperator.getAll(
            WhereCondition.builder()
                .column("uuid")
                .value(player.getUniqueId().toString())
                .build()
        );
        
        if (!results.isEmpty()) {
            PlayerData data = results.get(0);
            // 更新最后登录时间
            data.setLastLogin(System.currentTimeMillis());
            data.setName(player.getName()); // 更新可能变化的名字
            try {
                dataOperator.update(data);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            return data;
        }
        
        // 创建新数据
        PlayerData newData = new PlayerData();
        newData.setUuid(player.getUniqueId().toString());
        newData.setName(player.getName());
        newData.setBalance(0.0);
        newData.setLastLogin(System.currentTimeMillis());
        newData.setVip(false);
        
        dataOperator.insert(newData);
        return newData;
    }
    
    /**
     * 增加余额
     */
    public void addBalance(String uuid, double amount) {
        List<PlayerData> results = dataOperator.getAll(
            WhereCondition.builder().column("uuid").value(uuid).build()
        );
        
        if (!results.isEmpty()) {
            PlayerData data = results.get(0);
            dataOperator.update("balance", data.getBalance() + amount, data.getId());
        }
    }
    
    /**
     * 获取财富排行榜
     */
    public List<PlayerData> getTopBalances(int limit) {
        List<PlayerData> all = dataOperator.getAll();
        return all.stream()
            .sorted((a, b) -> Double.compare(b.getBalance(), a.getBalance()))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * 获取 VIP 玩家
     */
    public List<PlayerData> getVipPlayers() {
        return dataOperator.getAll(
            WhereCondition.builder().column("is_vip").value(true).build()
        );
    }
    
    /**
     * 搜索玩家
     */
    public List<PlayerData> searchByName(String keyword) {
        return dataOperator.getLike("name", keyword, Condition.LikeType.Contains);
    }
}
```

---

## 最佳实践

### 推荐做法

1. **使用 Lombok 简化实体类**
   ```java
   @Data
   @Builder
   @NoArgsConstructor
   @AllArgsConstructor
   @EqualsAndHashCode(callSuper = true)
   @Table("my_table")
   public class MyEntity extends AbstractDataEntity {
       // ...
   }
   ```

2. **为常用查询封装方法**
   ```java
   public PlayerData findByUuid(String uuid) {
       List<PlayerData> results = dataOperator.getAll(
           WhereCondition.builder().column("uuid").value(uuid).build()
       );
       return results.isEmpty() ? null : results.get(0);
   }
   ```

3. **使用服务层封装数据操作**

4. **考虑数据迁移**
   - 添加新字段时提供默认值
   - 避免删除或重命名字段

5. **为大表使用分页查询**
   ```java
   // 避免
   List<PlayerData> all = dataOperator.getAll(); // 可能很慢
   
   // 推荐
   List<PlayerData> page = dataOperator.page(1, 100);
   ```

### 避免做法

1. **避免在主线程进行大量数据操作**
   ```java
   // 在异步线程中操作
   Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
       List<PlayerData> data = dataOperator.getAll();
       // 处理数据...
   });
   ```

2. **避免存储过多冗余数据**

3. **避免频繁的单条更新**
   - 批量操作优于多次单条操作

4. **避免在实体中存储非序列化对象**
   ```java
   // 错误 - Location 无法直接序列化
   @Column("location")
   private Location location;
   
   // 正确 - 分开存储
   @Column("world")
   private String world;
   @Column(value = "x", type = "DOUBLE")
   private double x;
   // ...
   ```

---

> **下一步**: 阅读 [配置管理](./CONFIG_SYSTEM.md) 了解配置系统
