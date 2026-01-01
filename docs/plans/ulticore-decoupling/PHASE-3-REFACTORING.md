# Phase 3: 重构现有代码

**状态**: ✅ 已完成  
**完成时间**: 2025-12-31  
**前置条件**: Phase 2 完成

---

## 目标

- 将 `VersionWrapper` 接口标记为 `@Deprecated`，方法改为委托给 `VersionUtils`
- 修改所有直接使用 `getVersionWrapper()` 的代码，改用 `VersionUtils`
- 修改 `UltiTools.java` 使用 `DefaultVersionWrapper`

---

## 任务清单

### 3.1 重构 VersionWrapper.java

**文件**: `src/main/java/com/ultikits/ultitools/interfaces/VersionWrapper.java`  
**类型**: 完全替换

```java
package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.Sounds;
import com.ultikits.ultitools.utils.VersionUtils;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Version wrapper interface.
 * <p>
 * 版本包装器接口
 *
 * @deprecated Use {@link VersionUtils} static methods instead.
 *             This interface is kept for backward compatibility only.
 *             Will be removed in a future version.
 *             <p>
 *             请改用 {@link VersionUtils} 静态方法。
 *             此接口仅为向后兼容保留，将在未来版本中移除。
 */
@Deprecated
public interface VersionWrapper {

    @Deprecated
    default ItemStack getColoredPlaneGlass(Colors plane) {
        return VersionUtils.getColoredPlaneGlass(plane);
    }

    @Deprecated
    default ItemStack getSign() {
        return VersionUtils.getSign();
    }

    @Deprecated
    default ItemStack getEndEye() {
        return VersionUtils.getEndEye();
    }

    @Deprecated
    default ItemStack getEmailMaterial(boolean isRead) {
        return VersionUtils.getEmailMaterial(isRead);
    }

    @Deprecated
    default ItemStack getHead(OfflinePlayer player) {
        return VersionUtils.getHead(player);
    }

    @Deprecated
    default ItemStack getGrassBlock() {
        return VersionUtils.getGrassBlock();
    }

    @Deprecated
    default Objective registerNewObjective(Scoreboard scoreboard, String name, String criteria, String displayName) {
        return VersionUtils.registerNewObjective(scoreboard, name, criteria, displayName);
    }

    @Deprecated
    default Sound getSound(Sounds sound) {
        return VersionUtils.getSound(sound);
    }

    @Deprecated
    default ItemStack getBed(Colors bedColor) {
        return VersionUtils.getBed(bedColor);
    }

    @Deprecated
    default int getItemDurability(ItemStack itemStack) {
        return VersionUtils.getItemDurability(itemStack);
    }

    @Deprecated
    default ItemStack getItemInHand(Player player, boolean isMainHand) {
        return VersionUtils.getItemInHand(player, isMainHand);
    }

    @Deprecated
    default void sendActionBar(Player player, String message) {
        VersionUtils.sendActionBar(player, message);
    }

    @Deprecated
    default void sendPlayerList(Player player, String header, String footer) {
        VersionUtils.sendPlayerList(player, header, footer);
    }

    @Deprecated
    default BlockFace getBlockFace(Block placedBlock) {
        return VersionUtils.getBlockFace(placedBlock);
    }
}
```

- [ ] 替换 VersionWrapper.java 内容

---

### 3.2 重构 Buttons.java

**文件**: `src/main/java/com/ultikits/ultitools/entities/Buttons.java`  
**修改**: 将 `UltiTools.getInstance().getVersionWrapper()` 替换为 `VersionUtils`

**修改前**:
```java
import com.ultikits.ultitools.UltiTools;

PREVIOUS("上一页", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED)),
NEXT("下一页", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED)),
BACK("返回", () -> UltiTools.getInstance().getVersionWrapper().getSign()),
QUIT("退出", () -> UltiTools.getInstance().getVersionWrapper().getEndEye()),
OK("确认", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GREEN)),
CANCEL("取消", () -> UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.RED));
```

**修改后**:
```java
import com.ultikits.ultitools.utils.VersionUtils;

PREVIOUS("上一页", () -> VersionUtils.getColoredPlaneGlass(Colors.RED)),
NEXT("下一页", () -> VersionUtils.getColoredPlaneGlass(Colors.RED)),
BACK("返回", VersionUtils::getSign),
QUIT("退出", VersionUtils::getEndEye),
OK("确认", () -> VersionUtils.getColoredPlaneGlass(Colors.GREEN)),
CANCEL("取消", () -> VersionUtils.getColoredPlaneGlass(Colors.RED));
```

- [ ] 修改 Buttons.java

---

### 3.3 重构 BaseInventoryPage.java

**文件**: `src/main/java/com/ultikits/ultitools/abstracts/gui/BaseInventoryPage.java`  
**修改**: 两处调用

**添加 import**:
```java
import com.ultikits.ultitools.utils.VersionUtils;
```

**修改 createBackgroundIcon() 方法** (约第 130 行):
```java
// 修改前
ItemStack glass = UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(Colors.GRAY);

// 修改后
ItemStack glass = VersionUtils.getColoredPlaneGlass(Colors.GRAY);
```

**修改 createActionButton() 方法** (约第 147 行):
```java
// 修改前
ItemStack glass = UltiTools.getInstance().getVersionWrapper().getColoredPlaneGlass(color);

// 修改后
ItemStack glass = VersionUtils.getColoredPlaneGlass(color);
```

- [ ] 修改 BaseInventoryPage.java

---

### 3.4 重构 UltiTools.java

**文件**: `src/main/java/com/ultikits/ultitools/UltiTools.java`

**修改 1**: 修改 import
```java
// 删除
import com.ultikits.ultitools.manager.SpigotVersionManager;

// 添加
import com.ultikits.ultitools.interfaces.impl.DefaultVersionWrapper;
```

**修改 2**: 添加 @Deprecated 到 versionWrapper 字段 (约第 60 行)
```java
/**
 * @deprecated Use {@link com.ultikits.ultitools.utils.VersionUtils} instead.
 */
@Deprecated
@Getter
private VersionWrapper versionWrapper;
```

**修改 3**: 替换初始化逻辑 (约第 184 行)
```java
// 删除这段
this.versionWrapper = new SpigotVersionManager().match();
if (this.versionWrapper == null) {
    Bukkit.getLogger().log(
            Level.SEVERE,
            "[UltiTools-API] Your server version isn't supported in UltiTools-API!"
    );
    return;
}

// 替换为
this.versionWrapper = new DefaultVersionWrapper();
```

- [ ] 修改 UltiTools.java imports
- [ ] 添加 @Deprecated 注解到 versionWrapper 字段
- [ ] 替换 versionWrapper 初始化逻辑

---

### 3.5 重构 UltiToolsBean.java

**文件**: `src/main/java/com/ultikits/ultitools/context/UltiToolsBean.java`  
**修改**: 添加 @Deprecated 注解

```java
/**
 * @deprecated Use {@link com.ultikits.ultitools.utils.VersionUtils} instead.
 */
@Deprecated
@Bean
public VersionWrapper getVersionWrapper() {
    return UltiTools.getInstance().getVersionWrapper();
}
```

- [ ] 修改 UltiToolsBean.java

---

## 验证步骤

```bash
# 验证编译
mvn compile -Dmaven.test.skip=true
```

- [ ] 所有修改后的文件编译通过
- [ ] 无编译警告（除了预期的 @Deprecated 警告）

---

## 完成标准

- [x] VersionWrapper.java 已标记为 @Deprecated 并委托给 VersionUtils
- [x] Buttons.java 已使用 VersionUtils
- [x] BaseInventoryPage.java 已使用 VersionUtils
- [x] UltiTools.java 已使用 DefaultVersionWrapper
- [x] UltiToolsBean.java 已添加 @Deprecated
- [x] 编译无错误

---

## 下一步

完成后继续 [Phase 4: 清理与删除](PHASE-4-CLEANUP.md)

