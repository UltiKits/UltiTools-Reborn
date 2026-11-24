package com.ultikits.ultitools.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.ultikits.ultitools.item.ItemRenderer;
import com.ultikits.ultitools.item.ItemSpec;
import com.ultikits.ultitools.item.impl.PaperItemRenderer;
import com.ultikits.ultitools.text.Messages;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.internal.Catalogue;
import com.ultikits.ultitools.text.internal.ConfiguredLocaleResolver;
import com.ultikits.ultitools.text.internal.TextEngine;
import com.ultikits.ultitools.ui.container.FrameworkContainerRuntime;
import com.ultikits.ultitools.ui.runtime.ContainerRuntime;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Exercises chest slots with the production item, text and container implementations on MockBukkit. */
class ChestItemRendererIntegrationTest extends ChestTestFixture {
    private String language = "auto";
    private ConfiguredLocaleResolver localeResolver;
    private TextEngine textEngine;
    private MockedStatic<Bukkit> inventoryFactory;
    private final List<ViewerRender> viewerRenders = new ArrayList<>();
    private final List<LocaleRender> localeRenders = new ArrayList<>();

    @BeforeEach void observeInventoryHandoffs() {
        inventoryFactory = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        inventoryFactory.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class)))
                .thenAnswer(call -> spy(server.createInventory(call.getArgument(0), call.getArgument(1, Integer.class),
                        call.getArgument(2, Component.class))));
    }

    @Override Messages createMessages() {
        Logger logger = Logger.getAnonymousLogger();
        localeResolver = new ConfiguredLocaleResolver(() -> language, () -> "en", logger);
        textEngine = new TextEngine(localeResolver, logger);
        textEngine.publish(new Catalogue.Builder("chest-test", Locale.ENGLISH)
                .put(Locale.ENGLISH, "icon.name", "Diamond")
                .put(Locale.ENGLISH, "icon.lore", "A precious gem")
                .put(Locale.CHINESE, "icon.name", "钻石")
                .put(Locale.CHINESE, "icon.lore", "珍贵的宝石").build(), this);
        return textEngine.messagesFor("chest-test");
    }

    @Override ItemRenderer createItemRenderer() {
        PaperItemRenderer renderer = spy(new PaperItemRenderer(messages, localeResolver));
        doAnswer(call -> {
            ItemStack result = (ItemStack) call.callRealMethod();
            localeRenders.add(new LocaleRender(call.getArgument(1, Locale.class), result));
            return result;
        }).when(renderer).render(any(ItemSpec.class), any(Locale.class));
        doAnswer(call -> {
            ItemStack result = (ItemStack) call.callRealMethod();
            viewerRenders.add(new ViewerRender(call.getArgument(1, Audience.class), result));
            return result;
        }).when(renderer).render(any(ItemSpec.class), any(Audience.class));
        return renderer;
    }

    @Override ContainerRuntime createContainerRuntime() {
        Thread containerThread = Thread.currentThread();
        return new FrameworkContainerRuntime(() -> containerThread.equals(Thread.currentThread()),
                (viewer, text) -> viewer.sendMessage(messages.render(text, viewer)));
    }

    @Override @AfterEach void disposeChest() {
        try {
            super.disposeChest();
        } finally {
            try {
                if (textEngine != null) {
                    textEngine.close();
                }
            } finally {
                if (inventoryFactory != null) {
                    inventoryFactory.close();
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"en-US,Diamond,A precious gem", "zh-CN,钻石,珍贵的宝石"})
    void openingRendersNameAndLoreForTheViewer(String locale, String name, String lore) {
        player.setLocale(Locale.forLanguageTag(locale));
        GridSession session = open(withIcon(localizedIcon()));
        assertIcon(top(), 0, player, name, lore);
        assertThat(session.isOpen()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"en-US,zh-CN,钻石,珍贵的宝石", "zh-CN,en-US,Diamond,A precious gem"})
    void equivalentFreshSnapshotRendersTheViewersChangedLocale(String original, String updated,
                                                               String name, String lore) {
        player.setLocale(Locale.forLanguageTag(original));
        Grid first = withIcon(localizedIcon());
        GridSession session = open(first);
        Inventory inventory = top();
        InventoryView view = player.getOpenInventory();
        assertIcon(inventory, 0, player, original.startsWith("en") ? "Diamond" : "钻石",
                original.startsWith("en") ? "A precious gem" : "珍贵的宝石");
        ItemStack before = viewerRenders.get(0).item();
        Grid next = withIcon(localizedIcon());
        assertThat(next).isEqualTo(first).isNotSameAs(first);
        player.setLocale(Locale.forLanguageTag(updated));
        session.update(next);
        tick();
        assertIcon(inventory, 1, player, name, lore);
        assertThat(viewerRenders.get(1).item().getData(DataComponentTypes.ITEM_NAME))
                .isNotEqualTo(before.getData(DataComponentTypes.ITEM_NAME));
        assertThat(player.getOpenInventory()).isSameAs(view);
        assertThat(top()).isSameAs(inventory);
        assertThat(session.result().toCompletableFuture()).isNotDone();
    }

    @Test void forcedLanguageOverridesTheViewerForBothOpeningAndRepainting() {
        language = "en";
        player.setLocale(Locale.SIMPLIFIED_CHINESE);
        GridSession session = open(withIcon(localizedIcon()));
        assertIcon(top(), 0, player, "Diamond", "A precious gem");
        language = "zh";
        session.update(withIcon(localizedIcon()));
        tick();
        assertIcon(top(), 1, player, "钻石", "珍贵的宝石");
        assertThat(viewerRenders.get(1).item().getData(DataComponentTypes.ITEM_NAME))
                .isNotEqualTo(viewerRenders.get(0).item().getData(DataComponentTypes.ITEM_NAME));
        assertThat(session.isOpen()).isTrue();
    }

    @Test void theSameDescriptionIsRenderedIndependentlyForEachSessionsViewer() {
        SyncPlayer second = new SyncPlayer(server, "SecondViewer");
        server.addPlayer(second);
        player.setLocale(Locale.US);
        second.setLocale(Locale.SIMPLIFIED_CHINESE);
        Grid grid = withIcon(localizedIcon());
        GridSession firstSession = open(grid);
        assertIcon(top(), 0, player, "Diamond", "A precious gem");
        GridSession secondSession = service.show(second, grid);
        tick();
        Inventory secondInventory = second.getOpenInventory().getTopInventory();
        assertIcon(secondInventory, 1, second, "钻石", "珍贵的宝石");
        assertThat(viewerRenders.get(0).item()).isNotSameAs(viewerRenders.get(1).item());
        assertThat(viewerRenders.get(0).item().getData(DataComponentTypes.ITEM_NAME))
                .isEqualTo(Component.text("Diamond"));
        assertThat(secondInventory).isNotSameAs(top());
        assertThat(firstSession.isOpen()).isTrue();
        assertThat(secondSession.isOpen()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"STONE,65", "STONE,99", "DIAMOND_SWORD,2"})
    void iconAmountsAboveThePrototypeStackLimitAreNotClampedOrSplit(Material material, int amount) {
        ItemSpec icon = ItemSpec.builder(material).amount(amount).build();
        GridSession session = open(withIcon(icon));
        ItemStack displayed = top().getItem(0);
        assertThat(displayed.getAmount()).isEqualTo(amount).isGreaterThan(displayed.getMaxStackSize());
        assertThat(displayed.getType()).isEqualTo(material);
        assertThat(top().getItem(1)).isNull();
        assertThat(player.getItemOnCursor().isEmpty()).isTrue();
        assertThat(player.getInventory().isEmpty()).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    @Test void emptySlotsStayEmptyAndRemovingAnIconClearsItsRenderedComponents() {
        GridSession session = open(Grid.builder(Text.plain("Items"), 1).build());
        assertThat(top().isEmpty()).isTrue();
        assertThat(viewerRenders).isEmpty();
        assertThat(localeRenders).isEmpty();
        session.update(withIcon(localizedIcon()));
        tick();
        assertIcon(top(), 0, player, "Diamond", "A precious gem");
        ItemStack previous = viewerRenders.get(0).item();
        session.update(Grid.builder(Text.plain("Items"), 1).build());
        tick();
        assertThat(top().isEmpty()).isTrue();
        assertThat(viewerRenders).hasSize(1);
        assertThat(localeRenders).hasSize(1);
        assertThat(previous.getData(DataComponentTypes.ITEM_NAME)).isEqualTo(Component.text("Diamond"));
        assertThat(session.isOpen()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("unrenderableIcons")
    void openingRenderFailureCompletesUnsupportedWithoutOpeningAPartialScreen(ItemSpec icon) {
        Inventory previous = top();
        player.setItemOnCursor(stack(Material.STONE, 3));
        player.getInventory().setItem(0, stack(Material.DIRT, 4));
        ItemStack[] storage = player.getInventory().getStorageContents();
        GridSession session = open(withIcon(icon));
        tick();
        assertThat(session.isOpen()).isFalse();
        assertThat(session.result().toCompletableFuture())
                .isCompletedWithValue(new UiResult.Closed<Ack>(CloseReason.UNSUPPORTED));
        assertThat(top()).isSameAs(previous);
        assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 3));
        assertThat(player.getInventory().getStorageContents()).containsExactly(storage);
        GridSession replacement = open(withIcon(localizedIcon()));
        assertIcon(top(), 0, player, "Diamond", "A precious gem");
        assertThat(replacement.isOpen()).isTrue();
    }

    // MockBukkit clears the cursor on close; real servers return it to storage, covered by real-machine UAT.
    @ParameterizedTest
    @MethodSource("unrenderableIcons")
    void repaintRenderFailureClosesTheScreenAndPreservesRealPlayerItems(ItemSpec icon) {
        player = spy(player);
        GridSession session = open(withIcon(localizedIcon()));
        Inventory inventory = top();
        player.setItemOnCursor(stack(Material.STONE, 3));
        player.getInventory().setItem(0, stack(Material.DIRT, 4));
        ItemStack[] storage = player.getInventory().getStorageContents();
        clearInvocations(player);
        doAnswer(call -> {
            assertThat(player.getItemOnCursor()).isEqualTo(stack(Material.STONE, 3));
            assertThat(player.getInventory().getStorageContents()).containsExactly(storage);
            return call.callRealMethod();
        }).when(player).closeInventory();
        session.update(withIcon(icon));
        tick();
        tick();
        assertThat(session.isOpen()).isFalse();
        assertThat(session.result().toCompletableFuture())
                .isCompletedWithValue(new UiResult.Closed<Ack>(CloseReason.UNSUPPORTED));
        assertThat(top()).isNotSameAs(inventory);
        assertThat(top()).isNull();
        verify(player).closeInventory();
    }

    private ItemSpec localizedIcon() {
        return ItemSpec.builder(Material.DIAMOND).name(messages.key("icon.name"))
                .addLore(messages.key("icon.lore")).build();
    }

    private static Grid withIcon(ItemSpec icon) {
        return Grid.builder(Text.plain("Items"), 1).set(0, 0, icon, null).build();
    }

    private static ItemStack handedOffIcon(Inventory inventory) {
        ArgumentCaptor<ItemStack> icon = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory, atLeastOnce()).setItem(eq(0), icon.capture());
        return icon.getValue();
    }

    // MockBukkit loses components across clone; inspect real renderer outputs and inventory handoff separately.
    private void assertIcon(Inventory inventory, int callIndex, Audience viewer, String name, String lore) {
        assertThat(viewerRenders).hasSize(callIndex + 1);
        assertThat(localeRenders).hasSize(callIndex + 1);
        ViewerRender rendered = viewerRenders.get(callIndex);
        LocaleRender localized = localeRenders.get(callIndex);
        assertThat(rendered.viewer()).isSameAs(viewer);
        assertThat(localized.locale()).isEqualTo(localeResolver.resolve(viewer));
        ItemStack icon = rendered.item();
        assertThat(icon).isSameAs(localized.item());
        assertThat(icon.getData(DataComponentTypes.ITEM_NAME)).isEqualTo(Component.text(name));
        assertThat(icon.getData(DataComponentTypes.LORE).lines()).containsExactly(
                Component.text(lore).decoration(TextDecoration.ITALIC, false));
        ItemStack handedOff = handedOffIcon(inventory);
        assertThat(handedOff.getType()).isEqualTo(icon.getType());
        assertThat(handedOff.getAmount()).isEqualTo(icon.getAmount());
    }

    private record ViewerRender(Audience viewer, ItemStack item) { }

    private record LocaleRender(Locale locale, ItemStack item) { }

    private static Stream<ItemSpec> unrenderableIcons() {
        return Stream.of(ItemSpec.builder(Key.key("minecraft:water")).build(),
                ItemSpec.builder(Key.key("minecraft:diamond"))
                        .lore(Collections.nCopies(300, Text.plain("line"))).build());
    }
}
