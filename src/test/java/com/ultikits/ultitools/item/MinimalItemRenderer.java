package com.ultikits.ultitools.item;

import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Messages;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** A test-only renderer exercising the real MockBukkit component and PDC support. */
final class MinimalItemRenderer implements ItemRenderer {
    private final Messages messages;
    private final LocaleResolver resolver;

    MinimalItemRenderer(Messages messages, LocaleResolver resolver) {
        this.messages = messages;
        this.resolver = resolver;
    }

    @Override
    public ItemStack render(ItemSpec spec, Locale locale) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(locale, "locale");
        Material material = Material.matchMaterial(spec.itemType().asString());
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("Unknown item type: " + spec.itemType());
        }
        ItemStack stack = new ItemStack(material, spec.amount());
        if (spec.name() != null) {
            stack.setData(DataComponentTypes.ITEM_NAME, messages.render(spec.name(), locale));
        }
        if (!spec.lore().isEmpty()) {
            stack.setData(DataComponentTypes.LORE,
                    ItemLore.lore(spec.lore().stream().map(line -> messages.render(line, locale)).toList()));
        }
        if (spec.model() != null) {
            stack.setData(DataComponentTypes.ITEM_MODEL, spec.model());
        }
        if (spec.glint() != null) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, spec.glint());
        }
        if (spec.tooltipHidden() || !spec.hiddenInTooltip().isEmpty()) {
            stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                    .hideTooltip(spec.tooltipHidden()).hiddenComponents(spec.hiddenInTooltip()).build());
        }
        for (DataPatch patch : spec.patches()) {
            switch (patch) {
                case DataPatch.Assign<?> assign -> assign(stack, assign);
                case DataPatch.Flag flag -> stack.setData(flag.type());
                case DataPatch.Remove remove -> stack.unsetData(remove.type());
            }
        }
        for (ItemMarker<?, ?> marker : spec.markers()) {
            mark(stack, marker);
        }
        return stack;
    }

    private static <T> void assign(ItemStack stack, DataPatch.Assign<T> patch) {
        stack.setData(patch.type(), patch.value());
    }

    private static <P, C> void mark(ItemStack stack, ItemMarker<P, C> marker) {
        stack.editPersistentDataContainer(data -> data.set(marker.key(), marker.type(), marker.value()));
    }

    @Override
    public ItemStack render(ItemSpec spec, Audience viewer) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(viewer, "viewer");
        return render(spec, resolver.resolve(viewer));
    }
}
