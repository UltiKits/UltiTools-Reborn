package com.ultikits.ultitools.abstracts.gui.declarative.widgets.navigation;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;

/**
 * 路由构建器接口。
 * <p>
 * 用于根据上下文构建 Widget。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
@FunctionalInterface
public interface RouteBuilder {

    /**
     * 构建 Widget。
     *
     * @param context BuildContext
     * @return 构建的 Widget
     */
    @NotNull
    Widget build(@NotNull BuildContext context);
}
