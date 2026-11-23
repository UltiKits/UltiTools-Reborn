package com.ultikits.ultitools.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.InMemoryMessages;
import com.ultikits.ultitools.text.LocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.MockBukkitHelper;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Real ItemStack/data-component assertions shared with the future production renderer. */
public abstract class AbstractItemRendererContractTest {
    private static final MessageKey NAME = MessageKey.of("sample", "name");
    private static final MessageKey LORE = MessageKey.of("sample", "lore");
    private ServerMock server;
    private ItemRenderer renderer;

    /**
     * Creates the renderer under test with the supplied injected text and locale services.
     * A production subclass returns the real renderer, not a mocked ItemStack or an assertion adapter.
     * The contract starts MockBukkit before calling this method and cleans it up after each test.
     */
    protected abstract ItemRenderer createRenderer(Messages messages, LocaleResolver localeResolver);

    @BeforeEach
    void setUpItemRendererContract() {
        MockBukkitHelper.ensureCleanState();
        server = MockBukkit.mock();
        LocaleResolver resolver = new FixedLocaleResolver(Locale.CHINESE, Optional.empty());
        InMemoryMessages messages = new InMemoryMessages("sample", resolver);
        messages.sourceLanguage("sample", Locale.ENGLISH);
        messages.put(NAME, Locale.ENGLISH, "Diamond");
        messages.put(NAME, Locale.CHINESE, "钻石");
        messages.put(LORE, Locale.ENGLISH, "A localized item");
        messages.put(LORE, Locale.CHINESE, "本地化物品");
        renderer = createRenderer(messages, resolver);
    }

    @AfterEach
    void tearDownItemRendererContract() {
        MockBukkitHelper.safeUnmock();
    }

    @Test
    void render_locales_localizesItemNameAndLore() {
        ItemSpec spec = localizedSpec();
        ItemStack english = renderer.render(spec, Locale.US);
        ItemStack chinese = renderer.render(spec, Locale.SIMPLIFIED_CHINESE);
        assertThat(plain(english.getData(DataComponentTypes.ITEM_NAME))).isEqualTo("Diamond");
        assertThat(plain(chinese.getData(DataComponentTypes.ITEM_NAME))).isEqualTo("钻石");
        assertThat(english.getData(DataComponentTypes.LORE).lines().stream()
                .map(AbstractItemRendererContractTest::plain)).containsExactly("A localized item");
        assertThat(chinese.getData(DataComponentTypes.LORE).lines().stream()
                .map(AbstractItemRendererContractTest::plain)).containsExactly("本地化物品");
    }

    @Test
    void render_eachCall_returnsIndependentStack() {
        ItemSpec spec = localizedSpec();
        ItemStack first = renderer.render(spec, Locale.US);
        ItemStack second = renderer.render(spec, Locale.US);
        assertThat(first).isNotSameAs(second);
        first.setAmount(3);
        first.setData(DataComponentTypes.ITEM_NAME, Component.text("changed"));
        assertThat(second.getAmount()).isEqualTo(1);
        assertThat(plain(second.getData(DataComponentTypes.ITEM_NAME))).isEqualTo("Diamond");
        assertThat(spec.amount()).isEqualTo(1);
    }

    @Test
    void render_marker_roundTripsTypedPersistentData() {
        NamespacedKey key = new NamespacedKey("sample", "marker");
        ItemSpec spec = localizedSpec().toBuilder().marker(key, PersistentDataType.STRING, "typed-value").build();
        ItemStack stack = renderer.render(spec, Locale.US);
        assertThat(stack.getPersistentDataContainer().get(key, PersistentDataType.STRING)).isEqualTo("typed-value");
    }

    @Test
    void render_unknownItemKey_isRejected() {
        ItemSpec unknown = ItemSpec.builder(Key.key("sample:unknown_item")).build();
        assertThatThrownBy(() -> renderer.render(unknown, Locale.US)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void render_audienceOverload_usesResolvedLocale() {
        PlayerMock player = server.addPlayer();
        player.setLocale(Locale.US);
        assertThat(plain(renderer.render(localizedSpec(), player).getData(DataComponentTypes.ITEM_NAME)))
                .isEqualTo("Diamond");
        player.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertThat(plain(renderer.render(localizedSpec(), player).getData(DataComponentTypes.ITEM_NAME)))
                .isEqualTo("钻石");
        assertThat(plain(renderer.render(localizedSpec(), server.getConsoleSender())
                .getData(DataComponentTypes.ITEM_NAME))).isEqualTo("钻石");
    }

    @Test
    void render_typedAttributesAndOrderedPatches_areApplied() {
        Key model = Key.key("sample:model");
        ItemSpec spec = localizedSpec().toBuilder().amount(2).model(model).glint(true).tooltipHidden(true)
                .data(DataComponentTypes.MAX_STACK_SIZE, 16).data(DataComponentTypes.MAX_STACK_SIZE, 32)
                .data(DataComponentTypes.MAX_DAMAGE, 20).remove(DataComponentTypes.MAX_DAMAGE).build();
        ItemStack stack = renderer.render(spec, Locale.US);
        assertThat(stack.getAmount()).isEqualTo(2);
        assertThat(stack.getData(DataComponentTypes.ITEM_MODEL)).isEqualTo(model);
        assertThat(stack.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)).isTrue();
        assertThat(stack.getData(DataComponentTypes.TOOLTIP_DISPLAY).hideTooltip()).isTrue();
        assertThat(stack.getData(DataComponentTypes.MAX_STACK_SIZE)).isEqualTo(32);
        assertThat(stack.getData(DataComponentTypes.MAX_DAMAGE)).isNull();
        ItemSpec emptyLore = ItemSpec.builder(Key.key("minecraft:diamond")).lore(List.of()).build();
        assertThat(renderer.render(emptyLore, Locale.US).getData(DataComponentTypes.LORE)).isNull();
    }

    @Test
    void render_nullArguments_areRejectedByName() {
        assertThatNullPointerException().isThrownBy(() -> renderer.render(null, Locale.US)).withMessage("spec");
        assertThatNullPointerException().isThrownBy(() -> renderer.render(localizedSpec(), (Locale) null))
                .withMessage("locale");
        assertThatNullPointerException().isThrownBy(() -> renderer.render(localizedSpec(),
                (net.kyori.adventure.audience.Audience) null)).withMessage("viewer");
    }

    private static ItemSpec localizedSpec() {
        return ItemSpec.builder(Key.key("minecraft:diamond")).name(Text.of(NAME)).addLore(Text.of(LORE)).build();
    }

    private static String plain(Component component) {
        assertThat(component).isNotNull();
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
