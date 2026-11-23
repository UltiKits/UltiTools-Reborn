package com.ultikits.ultitools.item;

import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Turns item descriptions into stacks. Framework singleton; thread-safe; requires a running server. */
@ApiStatus.NonExtendable
public interface ItemRenderer {
    /**
     * Creates a new independent stack for the locale: typed attributes first, then patches in order,
     * then persistent-data markers. Text uses the framework's Messages lookup order.
     * @throws IllegalArgumentException if the item type is not an item in the running server's registry
     */
    ItemStack render(ItemSpec spec, Locale locale);

    /** Renders using {@code localeResolver.resolve(viewer)}. */
    ItemStack render(ItemSpec spec, Audience viewer);
}
