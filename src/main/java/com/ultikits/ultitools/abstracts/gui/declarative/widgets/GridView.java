package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.util.SlotUtils;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * GridView 是一个网格布局 Widget，将子 Widget 按网格排列。
 * <p>
 * 支持：
 * <ul>
 *   <li>指定起始槽位和行列数</li>
 *   <li>自动计算子 Widget 位置</li>
 *   <li>支持数据列表自动映射</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 手动指定子 Widget
 * GridView.builder()
 *     .startSlot(10)
 *     .rows(4)
 *     .columns(7)
 *     .children(buttons)
 *     .build();
 *
 * // 从数据列表自动生成
 * GridView.<ItemStack>builder()
 *     .startSlot(10)
 *     .columns(7)
 *     .items(itemList)
 *     .itemBuilder(item -> ItemDisplay.builder(item).build())
 *     .build();
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 * @param <T> 数据类型（如果使用数据列表）
 */
public class GridView<T> extends Widget {

    private final int startSlot;
    private final int columns;
    private final int maxRows;
    @NotNull
    private final List<Widget> children;

    private GridView(@NotNull Builder<T> builder) {
        super(builder.key);
        this.startSlot = builder.startSlot;
        this.columns = builder.columns;
        this.maxRows = builder.maxRows;
        this.children = Collections.unmodifiableList(new ArrayList<>(builder.children));
    }

    /**
     * 创建 Builder。
     *
     * @param <T> 数据类型
     * @return Builder
     */
    @NotNull
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public int getStartSlot() {
        return startSlot;
    }

    public int getColumns() {
        return columns;
    }

    public int getMaxRows() {
        return maxRows;
    }

    @NotNull
    public List<Widget> getChildren() {
        return children;
    }

    /**
     * 计算子 Widget 的实际槽位。
     *
     * @param childIndex 子 Widget 索引
     * @return 槽位索引
     */
    public int calculateSlotForChild(int childIndex) {
        int row = childIndex / columns;
        int col = childIndex % columns;
        return SlotUtils.toSlotIndex(startSlot, row, col);
    }

    @Override
    @NotNull
    public Element createElement() {
        return new GridViewElement(this);
    }

    /**
     * Builder for GridView.
     */
    public static class Builder<T> implements WidgetBuilder<GridView<T>> {
        private int startSlot = 0;
        private int columns = 9;
        private int maxRows = 6;
        @NotNull
        private List<Widget> children = new ArrayList<>();
        @Nullable
        private SlotKey key;

        public Builder<T> startSlot(int startSlot) {
            this.startSlot = startSlot;
            return this;
        }

        public Builder<T> columns(int columns) {
            this.columns = columns;
            return this;
        }

        public Builder<T> rows(int rows) {
            this.maxRows = rows;
            return this;
        }

        public Builder<T> child(@NotNull Widget child) {
            this.children.add(child);
            return this;
        }

        public Builder<T> children(@NotNull List<Widget> children) {
            this.children.addAll(children);
            return this;
        }

        /**
         * 设置数据列表和构建器函数。
         * <p>
         * 会自动将数据转换为 Widget，并计算位置。
         *
         * @param items       数据列表
         * @param itemBuilder 构建器函数
         * @return Builder
         */
        public Builder<T> items(@NotNull List<T> items, @NotNull Function<T, Widget> itemBuilder) {
            for (int i = 0; i < items.size(); i++) {
                T item = items.get(i);
                Widget widget = itemBuilder.apply(item);
                
                // 如果是 ItemDisplay，自动设置槽位
                if (widget instanceof ItemDisplay) {
                    ItemDisplay display = (ItemDisplay) widget;
                    int slot = calculateSlot(i);
                    // 重建 ItemDisplay 并设置槽位
                    widget = ItemDisplay.builder(display.getItemStack())
                            .slot(slot)
                            .name(display.getDisplayName())
                            .lore(display.getLore())
                            .onClick(display.getClickHandler())
                            .key(display.getKey() != null ? display.getKey() : SlotKey.of("item-" + i))
                            .build();
                }
                
                this.children.add(widget);
            }
            return this;
        }

        private int calculateSlot(int index) {
            int row = index / columns;
            int col = index % columns;
            return SlotUtils.toSlotIndex(startSlot, row, col);
        }

        public Builder<T> key(@Nullable SlotKey key) {
            this.key = key;
            return this;
        }

        public Builder<T> key(@NotNull String key) {
            this.key = SlotKey.of(key);
            return this;
        }

        @Override
        @NotNull
        public GridView<T> build() {
            return new GridView<>(this);
        }
    }
}
