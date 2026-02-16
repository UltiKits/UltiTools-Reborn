package com.ultikits.ultitools.abstracts.gui.declarative.widgets.navigation;

import com.ultikits.ultitools.abstracts.gui.declarative.core.BuildContext;
import com.ultikits.ultitools.abstracts.gui.declarative.core.State;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import com.ultikits.ultitools.abstracts.gui.declarative.widgets.Container;
import org.jetbrains.annotations.NotNull;

import java.util.Stack;

/**
 * 导航器的状态类。
 * <p>
 * 管理路由历史记录。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class NavigatorState extends State<Navigator> {

    private final Stack<String> history = new Stack<>();

    @Override
    public void initState() {
        history.push(getWidget().getInitialRoute());
    }

    /**
     * 推入一个新的路由。
     *
     * @param routeName 路由名称
     */
    public void push(@NotNull String routeName) {
        if (!getWidget().getRoutes().containsKey(routeName)) {
            throw new IllegalArgumentException("Route not found: " + routeName);
        }
        setState(() -> history.push(routeName));
    }

    /**
     * 弹出当前路由。
     * 如果只有一个路由，则不做任何操作。
     */
    public void pop() {
        if (history.size() > 1) {
            setState(history::pop);
        }
    }

    /**
     * 替换当前路由。
     *
     * @param routeName 新的路由名称
     */
    public void pushReplacement(@NotNull String routeName) {
        if (!getWidget().getRoutes().containsKey(routeName)) {
            throw new IllegalArgumentException("Route not found: " + routeName);
        }
        setState(() -> {
            if (!history.isEmpty()) {
                history.pop();
            }
            history.push(routeName);
        });
    }

    /**
     * 检查是否可以弹出路由。
     *
     * @return 如果历史记录大于 1 则返回 true
     */
    public boolean canPop() {
        return history.size() > 1;
    }

    @Override
    public Widget build(BuildContext context) {
        if (history.isEmpty()) {
            return Container.builder().build();
        }
        String currentRoute = history.peek();
        RouteBuilder builder = getWidget().getRoutes().get(currentRoute);
        if (builder == null) {
            return Container.builder().build();
        }
        return builder.build(context);
    }
}
