package com.ultikits.testfixtures.configunconstructable;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;

/**
 * Only an {@code (int)} constructor - neither of the two idioms {@code ConfigManager.registerAll}
 * resolves ({@code (String)} first, then no-arg). Scanning this package must refuse the class by
 * name (D-03) instead of silently skipping it.
 * <p>
 * 只有一个 {@code (int)} 构造函数——既不是 {@code ConfigManager.registerAll} 能解析的两种写法
 * （{@code (String)} 优先，其次无参）中的任何一种。扫描这个包必须按名字拒绝这个类（D-03），
 * 而不是静默跳过。
 */
@ConfigEntity("config/unconstructable.yml")
public class UnconstructableConfigEntity extends AbstractConfigEntity {

    public UnconstructableConfigEntity(int notAStringOrNoArgConstructor) {
        super("config/unconstructable.yml");
    }
}
