package com.ultikits.testfixtures.registrationparity;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ContextEntry;

/**
 * A module main class whose {@code @ContextEntry} target has no accessible no-arg constructor
 * (see {@link ParityUnconstructableContextBean}) -- both entry points must log the existing
 * WARNING and continue assembling the rest of the container rather than failing the whole
 * registration (WIRE-06).
 * <br>
 * 一个 {@code @ContextEntry} 目标没有可访问无参构造器的模块主类（见
 * {@link ParityUnconstructableContextBean}）——两个入口点都必须记录既有的 WARNING 并继续装配
 * 容器的其余部分，而不是让整个注册失败（WIRE-06）。
 */
@ContextEntry(ParityUnconstructableContextBean.class)
public class ParityUnconstructableContextEntryModule extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }
}
