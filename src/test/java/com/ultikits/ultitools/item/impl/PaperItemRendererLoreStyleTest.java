package com.ultikits.ultitools.item.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

@Timeout(30)
class PaperItemRendererLoreStyleTest {
    private PaperItemRenderer renderer;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        MockBukkit.mock();
        LocaleResolver resolver = new FixedLocaleResolver(Locale.US, Optional.empty());
        renderer = new PaperItemRenderer(new InMemoryMessages("sample", resolver), resolver);
    }

    @AfterEach
    void tearDown() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void render_loreWithoutItalic_isNotItalic() {
        Component line = renderLore(Text.plain("plain line"));
        assertThat(line.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(line.color()).isNull();
    }

    @Test
    void render_loreExplicitlyItalic_staysItalic() {
        Component line = renderLore(Text.literal(Component.text("italic line", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, true)));
        assertThat(line.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.TRUE);
        assertThat(line.color()).isEqualTo(NamedTextColor.RED);
    }

    @Test
    void render_name_isNotRestyled() {
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND).name(Text.plain("name")).build();
        Component name = renderer.render(spec, Locale.US).getData(DataComponentTypes.ITEM_NAME);
        assertThat(name.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.NOT_SET);
        assertThat(name).isEqualTo(Component.text("name"));
    }

    private Component renderLore(Text line) {
        ItemSpec spec = ItemSpec.builder(Material.DIAMOND).addLore(line).build();
        ItemStack stack = renderer.render(spec, Locale.US);
        return stack.getData(DataComponentTypes.LORE).lines().getFirst();
    }
}
