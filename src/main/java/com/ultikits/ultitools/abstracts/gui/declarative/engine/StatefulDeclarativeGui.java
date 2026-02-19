package com.ultikits.ultitools.abstracts.gui.declarative.engine;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * StatefulDeclarativeGui 支持状态的声明式 GUI 基类。
 * <p>
 * 它内部管理一个 State 对象，提供 {@link #setState(Runnable)} 方法来触发重建。
 *
 * @param <T> State 类型
 */
public abstract class StatefulDeclarativeGui<T extends StatefulDeclarativeGui<T>.State> extends DeclarativeGui {

    @NotNull
    private T state;

    public StatefulDeclarativeGui(@NotNull Player player, @NotNull String id, @NotNull String title, int rows) {
        super(player, id, title, rows);
        this.state = createState();
        state.gui = this;
    }

    /**
     * 创建状态对象。
     *
     * @return 状态对象
     */
    @NotNull
    protected abstract T createState();

    /**
     * 获取状态对象。
     *
     * @return 状态对象
     */
    @NotNull
    protected T getState() {
        return state;
    }

    /**
     * 设置状态并触发重建。
     *
     * @param action 状态修改操作
     */
    protected void setState(@NotNull Runnable action) {
        action.run();
        markNeedsBuild();
    }

    /**
     * 声明式 GUI 的状态基类。
     */
    public abstract class State {
        protected StatefulDeclarativeGui<T> gui;

        /**
         * 触发重建。
         */
        protected void setState(@NotNull Runnable action) {
            if (gui != null) {
                gui.setState(action);
            }
        }

        /**
         * 获取关联的 GUI。
         *
         * @return GUI 实例
         */
        @NotNull
        protected StatefulDeclarativeGui<T> getGui() {
            if (gui == null) {
                throw new IllegalStateException("State not attached to GUI");
            }
            return gui;
        }

        /**
         * 获取玩家。
         *
         * @return 玩家
         */
        @NotNull
        protected Player getPlayer() {
            if (gui == null) {
                throw new IllegalStateException("State not attached to GUI");
            }
            return gui.getPlayer();
        }
    }
}
