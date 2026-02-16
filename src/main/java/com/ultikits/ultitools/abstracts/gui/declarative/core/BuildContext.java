package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * BuildContext 是构建 Widget 树时的上下文信息。
 * <p>
 * 它包含构建过程中需要访问的信息，如：
 * <ul>
 *   <li>当前玩家</li>
 *   <li>GUI 配置（行数、标题等）</li>
 *   <li>父 Widget 的继承属性</li>
 *   <li>自定义的上下文数据</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * @Override
 * public Widget build(BuildContext context) {
 *     Player player = context.getPlayer();
 *     int rows = context.getRows();
 *     
 *     return Container.builder()
 *         .title("Welcome, " + player.getName())
 *         .rows(rows)
 *         .child(buildContent(context))
 *         .build();
 * }
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class BuildContext {

    @NotNull
    private final Player player;
    
    @NotNull
    private final String guiId;
    
    private final int rows;
    
    @Nullable
    private final Element parentElement;
    
    @NotNull
    private final Map<String, Object> inheritedProperties;

    private BuildContext(@NotNull Builder builder) {
        this.player = builder.player;
        this.guiId = builder.guiId;
        this.rows = builder.rows;
        this.parentElement = builder.parentElement;
        this.inheritedProperties = new HashMap<>(builder.inheritedProperties);
    }

    /**
     * 创建根 BuildContext。
     *
     * @param player 玩家
     * @param guiId  GUI ID
     * @param rows   行数
     * @return 新的 BuildContext
     */
    @NotNull
    public static BuildContext root(@NotNull Player player, @NotNull String guiId, int rows) {
        return new Builder(player, guiId, rows).build();
    }

    /**
     * 为此 Context 创建一个子 Context。
     * 用于传递父 Widget 的属性给子 Widget。
     *
     * @return 子 BuildContext
     */
    @NotNull
    public BuildContext child(@NotNull Element parentElement) {
        return new Builder(this)
                .parentElement(parentElement)
                .build();
    }

    // Getters

    /**
     * 获取当前玩家。
     *
     * @return 玩家
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * 获取 GUI ID。
     *
     * @return GUI ID
     */
    @NotNull
    public String getGuiId() {
        return guiId;
    }

    /**
     * 获取 GUI 行数。
     *
     * @return 行数
     */
    public int getRows() {
        return rows;
    }

    /**
     * 获取 GUI 槽位总数。
     *
     * @return 槽位总数
     */
    public int getSize() {
        return rows * 9;
    }

    /**
     * 获取父 Element。
     *
     * @return 父 Element，如果是根则返回 null
     */
    @Nullable
    public Element getParentElement() {
        return parentElement;
    }

    /**
     * 获取继承的属性值。
     *
     * @param key 属性名
     * @return 属性值，不存在则返回 null
     */
    @Nullable
    public Object getInheritedProperty(@NotNull String key) {
        return inheritedProperties.get(key);
    }

    /**
     * 检查是否有指定的继承属性。
     *
     * @param key 属性名
     * @return 如果存在则返回 true
     */
    public boolean hasInheritedProperty(@NotNull String key) {
        return inheritedProperties.containsKey(key);
    }

    /**
     * 创建一个新的 Builder，复制当前 Context 的配置。
     */
    @NotNull
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * BuildContext 的构建器。
     */
    public static class Builder {
        @NotNull
        private final Player player;
        @NotNull
        private final String guiId;
        private int rows;
        @Nullable
        private Element parentElement;
        @NotNull
        private final Map<String, Object> inheritedProperties = new HashMap<>();

        public Builder(@NotNull Player player, @NotNull String guiId, int rows) {
            this.player = player;
            this.guiId = guiId;
            this.rows = rows;
        }

        private Builder(@NotNull BuildContext context) {
            this.player = context.player;
            this.guiId = context.guiId;
            this.rows = context.rows;
            this.parentElement = context.parentElement;
            this.inheritedProperties.putAll(context.inheritedProperties);
        }

        public Builder rows(int rows) {
            this.rows = rows;
            return this;
        }

        public Builder parentElement(@Nullable Element parentElement) {
            this.parentElement = parentElement;
            return this;
        }

        public Builder inheritedProperty(@NotNull String key, @Nullable Object value) {
            if (value == null) {
                this.inheritedProperties.remove(key);
            } else {
                this.inheritedProperties.put(key, value);
            }
            return this;
        }

        public BuildContext build() {
            return new BuildContext(this);
        }
    }
}
