# UltiCleaner

**高性能 Minecraft 服务器清理插件** - 自动清理地面物品、实体和闲置区块，支持智能清理、TPS 自适应和分批处理。

[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://www.oracle.com/java/)
[![Spigot](https://img.shields.io/badge/Spigot-1.13--1.21-yellow.svg)](https://www.spigotmc.org/)
[![Paper](https://img.shields.io/badge/Paper-Compatible-blue.svg)](https://papermc.io/)

## ✨ 功能特性

### 🧹 基础清理功能
- **物品清理** - 定时清理地面掉落物，支持白名单和新掉落物保护
- **实体清理** - 定时清理指定类型的生物，保护命名/拴绳/驯服实体
- **区块卸载** - 自动卸载远离玩家的闲置区块，释放服务器内存

### 🧠 智能清理系统
- **阈值触发** - 当实体数量超过配置阈值时自动触发清理
- **冷却控制** - 防止短时间内重复触发智能清理
- **TPS 自适应** - 根据服务器 TPS 动态调整清理阈值
  - TPS < 18: 阈值降低 30%
  - TPS < 15: 阈值降低 50%

### ⚡ 性能优化
- **分批处理** - 清理操作分批执行，每 tick 处理固定数量，避免卡顿
- **异步事件** - 清理完成事件异步触发，不阻塞主线程
- **Paper 兼容** - 自动检测 Paper 服务器，使用优化 API

### 🔌 扩展性
- **自定义事件** - 提供 `PreItemCleanEvent`、`PreEntityCleanEvent`、`PreChunkUnloadEvent` 和 `CleanCompleteEvent`
- **可取消清理** - 其他插件可监听事件并取消特定清理操作
- **统计回调** - 清理完成后触发事件，包含清理数量和耗时

## 📦 安装

1. 下载最新版本的 UltiCleaner
2. 将 JAR 文件放入 `plugins/UltiTools/plugins/` 目录
3. 重启服务器或执行 `/ul reload`
4. 编辑 `plugins/UltiTools/UltiCleaner/config/cleaner.yml` 配置

## 🎮 命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/clean items` | `ulticleaner.clean` | 立即清理地面物品 |
| `/clean entities` | `ulticleaner.clean` | 立即清理实体 |
| `/clean all` | `ulticleaner.clean` | 清理所有物品和实体 |
| `/clean chunks` | `ulticleaner.clean` | 卸载闲置区块 |
| `/clean check` | `ulticleaner.clean` | 查看服务器实体统计 |
| `/clean status` | `ulticleaner.clean` | 查看清理状态和倒计时 |

## ⚙️ 配置文件

```yaml
# ============ 物品清理 ============
item:
  enabled: true                    # 启用物品清理
  interval: 300                    # 清理间隔（秒）
  warn-times: [60, 30, 10, 5, 3, 2, 1]  # 警告时间点
  whitelist:                       # 物品白名单
    - DIAMOND
    - EMERALD
    - NETHER_STAR
  ignore-named: true               # 忽略有自定义名称的物品
  ignore-recent: 30                # 忽略刚掉落的物品（秒）

# ============ 实体清理 ============
entity:
  enabled: true                    # 启用实体清理
  interval: 600                    # 清理间隔（秒）
  warn-times: [60, 30, 10, 5, 3, 2, 1]
  types:                           # 要清理的实体类型
    - ZOMBIE
    - SKELETON
    - CREEPER
  whitelist-named: true            # 不清理有名称的实体
  whitelist-leashed: true          # 不清理被拴住的实体
  whitelist-tamed: true            # 不清理被驯服的实体

# ============ 智能清理 ============
smart:
  enabled: false                   # 启用智能清理
  item-threshold: 2000             # 物品数量阈值
  mob-threshold: 1000              # 生物数量阈值
  cooldown: 60                     # 冷却时间（秒）

# ============ 分批处理 ============
batch:
  size: 50                         # 每tick清理数量
  show-progress: false             # 显示清理进度

# ============ TPS 自适应 ============
tps:
  adaptive-enabled: true           # 启用TPS自适应
  sample-window: "1m"              # 采样窗口（1m/5m/15m）
  low-threshold: 18.0              # 低TPS阈值
  critical-threshold: 15.0         # 严重低TPS阈值
  low-reduction: 30                # 低TPS阈值降低百分比
  critical-reduction: 50           # 严重低TPS阈值降低百分比

# ============ 区块卸载 ============
chunk:
  enabled: false                   # 启用区块卸载
  max-distance: 20                 # 最大区块距离
  batch-size: 5                    # 每tick卸载数量
  timeout: 5                       # 异步超时（秒）

# ============ 世界设置 ============
worlds:
  blacklist:                       # 不进行清理的世界
    - world_creative
```

## 🔧 开发者 API

### 监听清理事件

```java
@EventListener
public class CleanerListener implements Listener {
    
    @EventHandler
    public void onPreItemClean(PreItemCleanEvent event) {
        // 取消特定世界的清理
        if (event.getWorld() != null && 
            event.getWorld().getName().equals("protected_world")) {
            event.setCancelled(true);
            return;
        }
        
        // 从清理列表中移除特定物品
        event.getItemUuids().removeIf(uuid -> {
            Entity entity = Bukkit.getEntity(uuid);
            return entity != null && isProtectedItem(entity);
        });
    }
    
    @EventHandler
    public void onCleanComplete(CleanCompleteEvent event) {
        // 记录清理统计
        log.info("Cleaned {} {} in {}", 
            event.getCleanedCount(),
            event.getCleanType(),
            event.getFormattedDuration());
    }
}
```

### 事件类型

| 事件 | 触发时机 | 可取消 |
|------|----------|--------|
| `PreItemCleanEvent` | 物品清理前 | ✅ |
| `PreEntityCleanEvent` | 实体清理前 | ✅ |
| `PreChunkUnloadEvent` | 区块卸载前 | ✅ |
| `CleanCompleteEvent` | 清理完成后 | ❌ |

## 🆚 与旧版对比

| 功能 | UltiCleaner 2.0 | 旧版 UltiTools |
|------|-----------------|----------------|
| 物品清理 | ✅ | ✅ |
| 实体清理 | ✅ | ✅ |
| 智能清理 | ✅ | ✅ |
| 区块卸载 | ✅ | ✅ |
| **分批处理** | ✅ | ❌ |
| **TPS 自适应** | ✅ | ❌ |
| **自定义事件** | ✅ | ❌ |
| **Paper 优化** | ✅ | ❌ |
| 物品白名单 | ✅ | ❌ |
| 忽略新掉落物 | ✅ | ❌ |
| 忽略拴绳实体 | ✅ | ❌ |
| 实体清理警告 | ✅ | ❌ |

## 📊 性能建议

### 小型服务器 (< 20 玩家)
```yaml
item:
  interval: 600        # 10分钟清理一次
smart:
  enabled: false       # 关闭智能清理
chunk:
  enabled: false       # 关闭区块卸载
```

### 中型服务器 (20-100 玩家)
```yaml
item:
  interval: 300        # 5分钟清理一次
smart:
  enabled: true
  item-threshold: 1500
  mob-threshold: 800
batch:
  size: 100            # 增加批量大小
```

### 大型服务器 (100+ 玩家)
```yaml
item:
  interval: 180        # 3分钟清理一次
smart:
  enabled: true
  item-threshold: 1000  # 降低阈值
  mob-threshold: 500
tps:
  adaptive-enabled: true
  low-threshold: 19.0   # 更敏感的TPS检测
chunk:
  enabled: true
  max-distance: 15      # 更积极的区块卸载
```

## 🐛 问题反馈

如遇到问题，请在 [GitHub Issues](https://github.com/UltiKits/UltiTools-Reborn/issues) 提交，并附上：
- 服务器版本 (Spigot/Paper)
- UltiTools 版本
- 完整的错误日志
- 配置文件内容

## 📄 许可证

本项目基于 MIT 许可证开源。
