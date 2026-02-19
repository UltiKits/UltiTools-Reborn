package com.ultikits.ultitools.abstracts.gui.declarative.util;

import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;

/**
 * Widget 构建器的通用接口。
 *
 * @param <T> 构建的 Widget 类型
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
@FunctionalInterface
public interface WidgetBuilder<T extends Widget> {

    /**
     * 构建 Widget 实例。
     *
     * @return Widget 实例
     */
    @NotNull
    T build();
}
