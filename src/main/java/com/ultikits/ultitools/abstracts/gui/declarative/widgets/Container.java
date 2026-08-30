package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.*;
import com.ultikits.ultitools.abstracts.gui.declarative.util.WidgetBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Container 是一个可以包含多个子 Widget 的容器。
 * <p>
 * 它负责：
 * <ul>
 *   <li>管理子 Widget 列表</li>
 *   <li>为子 Widget 分配槽位</li>
 * </ul>
 *
 * <p><strong>使用示例：</strong></p>
 * <pre>{@code
 * Container.builder()
 *     .child(ItemDisplay.builder(item).build())
 *     .child(TextButton.builder().text("Click").build())
 *     .build();
 * }</pre>
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class Container extends Widget {

    @NotNull
    private final List<Widget> children;

    private Container(@NotNull Builder builder) {
        super(builder.key);
        this.children = Collections.unmodifiableList(new ArrayList<>(builder.children));
    }

    /**
     * 创建一个新的 Builder。
     *
     * @return Builder
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    @NotNull
    public List<Widget> getChildren() {
        return children;
    }

    @Override
    @NotNull
    public Element createElement() {
        return new ContainerElement(this);
    }

    /**
     * Builder for Container.
     */
    public static class Builder implements WidgetBuilder<Container> {
        private final List<Widget> children = new ArrayList<>();
        @Nullable
        private SlotKey key;

        public Builder child(@NotNull Widget child) {
            this.children.add(child);
            return this;
        }

        public Builder children(@NotNull List<Widget> children) {
            this.children.addAll(children);
            return this;
        }

        public Builder children(@NotNull Widget... children) {
            Collections.addAll(this.children, children);
            return this;
        }

        public Builder key(@Nullable SlotKey key) {
            this.key = key;
            return this;
        }

        public Builder key(@NotNull String key) {
            this.key = SlotKey.of(key);
            return this;
        }

        @Override
        @NotNull
        public Container build() {
            return new Container(this);
        }
    }
}
