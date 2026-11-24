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
     * Missing root text-service bindings cause an {@link IllegalStateException} on that first request;
     * after the bindings are registered, a later request can resolve the renderer.
     *
     * @param root the framework root container
     */
    public static void register(SimpleContainer root) {
        Objects.requireNonNull(root, "root");
        root.registerTypeSupplier(ItemRenderer.class, () -> {
            Messages messages = root.getBean(Messages.class);
            LocaleResolver localeResolver = root.getBean(LocaleResolver.class);
            if (messages == null || localeResolver == null) {
                throw new IllegalStateException(
                        "ItemRenderer requires Messages and LocaleResolver bound by type in the root container");
            }
            return new PaperItemRenderer(messages, localeResolver);
        });
    }
}
