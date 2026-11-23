package com.ultikits.ultitools.item.impl;

import com.ultikits.ultitools.item.DataPatch;
import com.ultikits.ultitools.item.ItemMarker;
import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.jetbrains.annotations.ApiStatus;

/**
 * The framework's {@link ItemRenderer} on Paper data components. Stateless and thread-safe;
 * requires a running server (or MockBukkit) for the item registry.
 * Air is rejected by {@link ItemSpec} at build time, not by membership in the item registry.
 */
@ApiStatus.Internal
public final class PaperItemRenderer implements ItemRenderer {
    private final Messages messages;
    private final LocaleResolver localeResolver;

    /**
     * Creates a renderer using the framework's text and locale services.
     *
     * @param messages the service that renders text in any module namespace
     * @param localeResolver the service that resolves a viewer's locale
     */
    public PaperItemRenderer(Messages messages, LocaleResolver localeResolver) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.localeResolver = Objects.requireNonNull(localeResolver, "localeResolver");
    }

    @Override
    public ItemStack render(ItemSpec spec, Locale locale) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(locale, "locale");
        ItemStack stack = createStack(spec);
        applyName(stack, spec, locale);
        applyLore(stack, spec, locale);
        applyModel(stack, spec);
        applyGlint(stack, spec);
        applyTooltip(stack, spec);
        applyPatches(stack, spec);
        for (ItemMarker<?, ?> marker : spec.markers()) {
            applyMarker(stack, marker);
        }
        return stack;
    }

    @Override
    public ItemStack render(ItemSpec spec, Audience viewer) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(viewer, "viewer");
        return render(spec, localeResolver.resolve(viewer));
    }

    private static ItemStack createStack(ItemSpec spec) {
        ItemType type = Registry.ITEM.get(spec.itemType());
        if (type == null) {
            throw new IllegalArgumentException("Not an item in this server's item registry: " + spec.itemType());
        }
        return type.createItemStack(spec.amount());
    }

    private void applyName(ItemStack stack, ItemSpec spec, Locale locale) {
        if (spec.name() != null) {
            stack.setData(DataComponentTypes.ITEM_NAME, messages.render(spec.name(), locale));
        }
    }

    private void applyLore(ItemStack stack, ItemSpec spec, Locale locale) {
        if (!spec.lore().isEmpty()) {
            stack.setData(DataComponentTypes.LORE,
                    ItemLore.lore(spec.lore().stream().map(line -> messages.render(line, locale)).toList()));
        }
    }

    private static void applyModel(ItemStack stack, ItemSpec spec) {
        if (spec.model() != null) {
            stack.setData(DataComponentTypes.ITEM_MODEL, spec.model());
        }
    }

    private static void applyGlint(ItemStack stack, ItemSpec spec) {
        if (spec.glint() != null) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, spec.glint());
        }
    }

    private static void applyTooltip(ItemStack stack, ItemSpec spec) {
        if (spec.tooltipHidden() || !spec.hiddenInTooltip().isEmpty()) {
            stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                    .hideTooltip(spec.tooltipHidden()).hiddenComponents(spec.hiddenInTooltip()).build());
        }
    }

    private static void applyPatches(ItemStack stack, ItemSpec spec) {
        for (DataPatch patch : spec.patches()) {
            switch (patch) {
                case DataPatch.Assign<?> assign -> applyAssignment(stack, assign);
                case DataPatch.Flag flag -> stack.setData(flag.type());
                case DataPatch.Remove remove -> stack.unsetData(remove.type());
            }
        }
    }

    private static <T> void applyAssignment(ItemStack stack, DataPatch.Assign<T> patch) {
        stack.setData(patch.type(), patch.value());
    }

    private static <P, C> void applyMarker(ItemStack stack, ItemMarker<P, C> marker) {
        stack.editPersistentDataContainer(data -> data.set(marker.key(), marker.type(), marker.value()));
    }
}
