package com.ultikits.ultitools.abstracts.gui.declarative.widgets.navigation;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 导航器 Widget。
 * <p>
 * 管理路由栈，并根据当前路由构建 Widget。
 * 通过 {@link Navigator#of(BuildContext)} 获取最近的导航器状态，进而进行页面跳转。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class Navigator extends StatefulWidget {

    @NotNull
    private final String initialRoute;
    @NotNull
    private final Map<String, RouteBuilder> routes;

    /**
     * 创建一个新的 Navigator。
     *
     * @param initialRoute 初始路由名称
     * @param routes       路由表
     */
    public Navigator(@NotNull String initialRoute, @NotNull Map<String, RouteBuilder> routes) {
        this.initialRoute = initialRoute;
        this.routes = routes;
    }

    @Override
    public State<Navigator> createState() {
        return new NavigatorState();
    }

    @NotNull
    public String getInitialRoute() {
        return initialRoute;
    }

    @NotNull
    public Map<String, RouteBuilder> getRoutes() {
        return routes;
    }

    /**
     * 获取最近的 NavigatorState。
     *
     * @param context BuildContext
     * @return NavigatorState，如果没有找到则返回 null
     */
    @Nullable
    public static NavigatorState of(@NotNull BuildContext context) {
        Element current = context.getParentElement();
        while (current != null) {
            if (current instanceof StatefulElement) {
                State<?> state = ((StatefulElement) current).getState();
                if (state instanceof NavigatorState) {
                    return (NavigatorState) state;
                }
            }
            current = current.getParent();
        }
        return null;
    }
}
