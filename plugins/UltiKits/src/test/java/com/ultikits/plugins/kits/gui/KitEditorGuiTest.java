package com.ultikits.plugins.kits.gui;

import com.ultikits.plugins.kits.model.KitDefinition;
import com.ultikits.plugins.kits.service.KitService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("KitEditorGui")
@ExtendWith(MockitoExtension.class)
class KitEditorGuiTest {

    @Mock
    private UltiToolsPlugin plugin;

    @Mock
    private KitService kitService;

    @Mock
    private Player player;

    @Mock
    private InventoryView inventoryView;

    @Mock
    private Inventory topInventory;

    private KitDefinition kit;
    private KitEditorGui gui;

    @BeforeEach
    void setUp() {
        lenient().when(plugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        kit = new KitDefinition();
        kit.setName("testkit");
        kit.setDisplayName("&aTest Kit");
        kit.setDescription(new ArrayList<>(Arrays.asList("&7A test kit", "&eFor testing")));

        gui = new KitEditorGui(player, plugin, kitService, kit);

        lenient().when(player.getOpenInventory()).thenReturn(inventoryView);
        lenient().when(inventoryView.getTopInventory()).thenReturn(topInventory);
    }

    // -----------------------------------------------------------------------
    // Constructor Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("stores kit reference")
        void storesKit() throws Exception {
            Field kitField = KitEditorGui.class.getDeclaredField("kit");
            kitField.setAccessible(true); // NOPMD
            assertThat(kitField.get(gui)).isSameAs(kit);
        }

        @Test
        @DisplayName("stores player reference")
        void storesPlayer() throws Exception {
            Field playerField = KitEditorGui.class.getDeclaredField("player");
            playerField.setAccessible(true); // NOPMD
            assertThat(playerField.get(gui)).isSameAs(player);
        }

        @Test
        @DisplayName("stores plugin reference")
        void storesPlugin() throws Exception {
            Field pluginField = KitEditorGui.class.getDeclaredField("plugin");
            pluginField.setAccessible(true); // NOPMD
            assertThat(pluginField.get(gui)).isSameAs(plugin);
        }

        @Test
        @DisplayName("stores kitService reference")
        void storesKitService() throws Exception {
            Field serviceField = KitEditorGui.class.getDeclaredField("kitService");
            serviceField.setAccessible(true); // NOPMD
            assertThat(serviceField.get(gui)).isSameAs(kitService);
        }

        @Test
        @DisplayName("can construct with different kit name")
        void differentKitName() throws Exception {
            KitDefinition otherKit = new KitDefinition();
            otherKit.setName("vipkit");
            otherKit.setDisplayName("&6VIP Kit");
            otherKit.setDescription(new ArrayList<>());

            KitEditorGui otherGui = new KitEditorGui(player, plugin, kitService, otherKit);

            Field kitField = KitEditorGui.class.getDeclaredField("kit");
            kitField.setAccessible(true); // NOPMD
            KitDefinition storedKit = (KitDefinition) kitField.get(otherGui);
            assertThat(storedKit.getName()).isEqualTo("vipkit");
        }
    }

    // -----------------------------------------------------------------------
    // Static Constants Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Static Constants Tests")
    class StaticConstantsTests {

        @Test
        @DisplayName("SAVE_SLOT is 45")
        void saveSlot() throws Exception {
            Field field = KitEditorGui.class.getDeclaredField("SAVE_SLOT");
            field.setAccessible(true); // NOPMD
            assertThat(field.getInt(null)).isEqualTo(45);
        }

        @Test
        @DisplayName("INFO_SLOT is 49")
        void infoSlot() throws Exception {
            Field field = KitEditorGui.class.getDeclaredField("INFO_SLOT");
            field.setAccessible(true); // NOPMD
            assertThat(field.getInt(null)).isEqualTo(49);
        }

        @Test
        @DisplayName("CANCEL_SLOT is 53")
        void cancelSlot() throws Exception {
            Field field = KitEditorGui.class.getDeclaredField("CANCEL_SLOT");
            field.setAccessible(true); // NOPMD
            assertThat(field.getInt(null)).isEqualTo(53);
        }

        @Test
        @DisplayName("ITEM_SLOTS is 45")
        void itemSlots() throws Exception {
            Field field = KitEditorGui.class.getDeclaredField("ITEM_SLOTS");
            field.setAccessible(true); // NOPMD
            assertThat(field.getInt(null)).isEqualTo(45);
        }
    }

    // -----------------------------------------------------------------------
    // HandleSave Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("HandleSave Tests")
    class HandleSaveTests {

        @Test
        @DisplayName("collects non-null non-air items from slots 0-44")
        void collectsItems() {
            ItemStack diamond = mock(ItemStack.class);
            when(diamond.getType()).thenReturn(Material.DIAMOND);
            ItemStack sword = mock(ItemStack.class);
            when(sword.getType()).thenReturn(Material.DIAMOND_SWORD);

            when(topInventory.getItem(0)).thenReturn(diamond);
            when(topInventory.getItem(1)).thenReturn(sword);
            for (int i = 2; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }

            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
            verify(kitService).saveKitItems(eq("testkit"), captor.capture());
            assertThat(captor.getValue()).hasSize(2);
            assertThat(captor.getValue()[0]).isSameAs(diamond);
            assertThat(captor.getValue()[1]).isSameAs(sword);
        }

        @Test
        @DisplayName("skips AIR items")
        void skipsAirItems() {
            ItemStack airItem = mock(ItemStack.class);
            when(airItem.getType()).thenReturn(Material.AIR);
            ItemStack stone = mock(ItemStack.class);
            when(stone.getType()).thenReturn(Material.STONE);

            when(topInventory.getItem(0)).thenReturn(airItem);
            when(topInventory.getItem(1)).thenReturn(stone);
            for (int i = 2; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }

            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
            verify(kitService).saveKitItems(eq("testkit"), captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue()[0]).isSameAs(stone);
        }

        @Test
        @DisplayName("skips null items")
        void skipsNullItems() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }

            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
            verify(kitService).saveKitItems(eq("testkit"), captor.capture());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("handles mixed null, AIR, and real items across slots")
        void mixedItems() {
            ItemStack airItem = mock(ItemStack.class);
            when(airItem.getType()).thenReturn(Material.AIR);
            ItemStack iron = mock(ItemStack.class);
            when(iron.getType()).thenReturn(Material.IRON_INGOT);
            ItemStack gold = mock(ItemStack.class);
            when(gold.getType()).thenReturn(Material.GOLD_INGOT);

            // slot 0: null, slot 1: AIR, slot 2: iron, slot 3-43: null, slot 44: gold
            when(topInventory.getItem(0)).thenReturn(null);
            when(topInventory.getItem(1)).thenReturn(airItem);
            when(topInventory.getItem(2)).thenReturn(iron);
            for (int i = 3; i < 44; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(topInventory.getItem(44)).thenReturn(gold);

            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
            verify(kitService).saveKitItems(eq("testkit"), captor.capture());
            assertThat(captor.getValue()).hasSize(2);
            assertThat(captor.getValue()[0]).isSameAs(iron);
            assertThat(captor.getValue()[1]).isSameAs(gold);
        }

        @Test
        @DisplayName("success sends save message and closes inventory")
        void successSendsMessage() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("已保存礼包").contains("testkit");
            verify(player).closeInventory();
        }

        @Test
        @DisplayName("success message includes kit name from kit definition")
        void successMessageIncludesKitName() {
            KitDefinition otherKit = new KitDefinition();
            otherKit.setName("vipkit");
            otherKit.setDisplayName("&6VIP");
            otherKit.setDescription(new ArrayList<>());
            KitEditorGui otherGui = new KitEditorGui(player, plugin, kitService, otherKit);

            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("vipkit"), any(ItemStack[].class))).thenReturn(true);

            otherGui.handleSave();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("vipkit");
        }

        @Test
        @DisplayName("failure sends error message and closes inventory")
        void failureSendsError() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(false);

            gui.handleSave();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(player).sendMessage(captor.capture());
            assertThat(captor.getValue()).contains("领取礼包时发生错误");
            verify(player).closeInventory();
        }

        @Test
        @DisplayName("always closes inventory regardless of result")
        void alwaysCloses() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(false);

            gui.handleSave();

            verify(player).closeInventory();
        }

        @Test
        @DisplayName("passes correct kit name to saveKitItems")
        void correctKitName() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            verify(kitService).saveKitItems(eq("testkit"), any(ItemStack[].class));
        }

        @Test
        @DisplayName("collects items from all 45 slots")
        void collectsFromAllSlots() {
            ItemStack item = mock(ItemStack.class);
            when(item.getType()).thenReturn(Material.STONE);

            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(item);
            }

            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
            verify(kitService).saveKitItems(eq("testkit"), captor.capture());
            assertThat(captor.getValue()).hasSize(45);
        }

        @Test
        @DisplayName("sends exactly one message on success")
        void singleMessageOnSuccess() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            verify(player, times(1)).sendMessage(anyString());
        }

        @Test
        @DisplayName("sends exactly one message on failure")
        void singleMessageOnFailure() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(false);

            gui.handleSave();

            verify(player, times(1)).sendMessage(anyString());
        }

        @Test
        @DisplayName("i18n is called for success message")
        void i18nCalledForSuccess() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(true);

            gui.handleSave();

            verify(plugin).i18n("已保存礼包: %s");
        }

        @Test
        @DisplayName("i18n is called for error message")
        void i18nCalledForError() {
            for (int i = 0; i < 45; i++) {
                when(topInventory.getItem(i)).thenReturn(null);
            }
            when(kitService.saveKitItems(eq("testkit"), any(ItemStack[].class))).thenReturn(false);

            gui.handleSave();

            verify(plugin).i18n("领取礼包时发生错误");
        }
    }
}
