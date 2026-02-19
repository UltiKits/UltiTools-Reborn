package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * SlotKey 用于在 Widget 树中唯一标识一个 Widget。
 * <p>
 * 在列表或动态内容中，SlotKey 对于 diff 算法至关重要。
 * 它允许框架识别哪些 Widget 是"同一个"（只是数据变化），
 * 哪些是新增或删除的，从而避免不必要的重建。
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>列表项需要稳定的 key 来保持状态（如滚动位置、选中状态）</li>
 *   <li>动态内容需要 key 来触发正确的动画或过渡</li>
 *   <li>复用 Element 以提高性能</li>
 * </ul>
 *
 * <p><strong>最佳实践：</strong></p>
 * <pre>{@code
 * // 好的做法：使用业务唯一标识作为 key
 * ListView.builder()
 *     .children(players.stream()
 *         .map(p -> PlayerWidget.builder()
 *             .key(SlotKey.of(p.getUniqueId().toString()))  // 稳定的 UUID
 *             .player(p)
 *             .build())
 *         .toList())
 *     .build();
 *
 * // 避免：使用列表索引作为 key（除非列表是静态的）
 * // 这样会导致数据更新时，key 与实际内容不匹配
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @see Widget#getKey()
 */
public final class SlotKey {

    @NotNull
    private final String value;

    private SlotKey(@NotNull String value) {
        this.value = value;
    }

    /**
     * 创建一个 SlotKey。
     *
     * @param value key 值，不能为空
     * @return 新的 SlotKey 实例
     * @throws IllegalArgumentException 如果 value 为空
     */
    @NotNull
    public static SlotKey of(@NotNull String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("SlotKey value cannot be empty");
        }
        return new SlotKey(value);
    }

    /**
     * 创建一个带有前缀的 SlotKey。
     *
     * @param prefix 前缀
     * @param value  key 值
     * @return 新的 SlotKey 实例
     */
    @NotNull
    public static SlotKey of(@NotNull String prefix, @NotNull String value) {
        return new SlotKey(prefix + ":" + value);
    }

    /**
     * 获取 key 的字符串值。
     *
     * @return key 值
     */
    @NotNull
    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SlotKey)) return false;
        SlotKey slotKey = (SlotKey) o;
        return value.equals(slotKey.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "SlotKey(" + value + ")";
    }
}
