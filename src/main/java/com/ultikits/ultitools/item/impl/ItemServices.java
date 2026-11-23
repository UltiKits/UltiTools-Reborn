package com.ultikits.ultitools.item.impl;

import com.ultikits.ultitools.context.SimpleContainer;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;
import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/** Registers framework-owned item services in the root container. */
@ApiStatus.Internal
public final class ItemServices {
    private ItemServices() {
        // Static registration helper; no instances are needed.
    }

    /**
     * Binds the renderer, resolving text services only when the renderer is first requested.
     *
     * @param root the framework root container
     */
    public static void register(SimpleContainer root) {
        Objects.requireNonNull(root, "root");
        root.registerTypeSupplier(ItemRenderer.class, () -> new PaperItemRenderer(
                root.getBean(Messages.class), root.getBean(LocaleResolver.class)));
    }
}
