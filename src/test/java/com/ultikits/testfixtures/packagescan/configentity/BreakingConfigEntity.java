package com.ultikits.testfixtures.packagescan.configentity;

import com.ultikits.testfixtures.packagescan.BreakableSuperclass;
import com.ultikits.ultitools.annotations.ConfigEntity;

/**
 * A {@code @ConfigEntity}-annotated fixture class that extends
 * {@link BreakableSuperclass} - when loaded through a class loader that blocks
 * {@code BreakableSuperclass} specifically, resolving this class's superclass during
 * {@code Class.forName(name, true, loader)} genuinely throws {@link NoClassDefFoundError}, the same
 * failure shape a module JAR referencing a Phase 7-removed symbol produces.
 * <br>
 * 继承自 {@link BreakableSuperclass} 的 {@code @ConfigEntity} fixture 类——当通过一个专门
 * 阻断 {@code BreakableSuperclass} 的类加载器加载时，{@code Class.forName(name, true, loader)}
 * 在解析本类父类时会真实抛出 {@link NoClassDefFoundError}，这与模块 JAR 引用 Phase 7
 * 移除符号时的失败形态完全一致。
 */
@ConfigEntity("config/breaking.yml")
public class BreakingConfigEntity extends BreakableSuperclass {
}
