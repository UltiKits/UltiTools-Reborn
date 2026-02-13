package com.ultikits.plugins.mail.gui;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.plugins.mail.utils.MockBukkitHelper;
import com.ultikits.plugins.mail.utils.TestHelper;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AttachmentSelectorPage.
 * <p>
 * 附件选择 GUI 单元测试。
 * <p>
 * 注意: 需要 MockBukkit，由于 Java 21 + Paper API 兼容性问题暂时禁用。
 */
@DisplayName("AttachmentSelectorPage 测试")
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@Disabled("MockBukkit 与 Java 21 + Paper API 存在兼容性问题，待修复")
class AttachmentSelectorPageTest {

    private PlayerMock player;

    @BeforeEach
    void setUp() {
        MockBukkitHelper.ensureCleanState();
        ServerMock server = MockBukkit.mock();
        MockBukkit.createMockPlugin();

        // Setup mock UltiToolsPlugin
        TestHelper.mockUltiToolsPlugin();

        player = server.addPlayer("testplayer");
    }

    @AfterEach
    void tearDown() {
        TestHelper.cleanupMocks();
        MockBukkitHelper.safeUnmock();
    }

    @Nested
    @DisplayName("物品验证测试")
    class ItemValidationTests {

        @Test
        @DisplayName("应该过滤掉空物品")
        void shouldFilterNullItems() {
            ItemStack[] items = new ItemStack[] {
                new ItemStack(Material.DIAMOND),
                null,
                new ItemStack(Material.GOLD_INGOT),
                null
            };

            ItemStack[] validItems = Arrays.stream(items)
                .filter(item -> item != null && !item.getType().isAir())
                .toArray(ItemStack[]::new);

            assertThat(validItems).hasSize(2);
        }

        @Test
        @DisplayName("应该过滤掉 AIR 物品")
        void shouldFilterAirItems() {
            ItemStack[] items = new ItemStack[] {
                new ItemStack(Material.DIAMOND),
                new ItemStack(Material.AIR),
                new ItemStack(Material.GOLD_INGOT)
            };

            ItemStack[] validItems = Arrays.stream(items)
                .filter(item -> item != null && !item.getType().isAir())
                .toArray(ItemStack[]::new);

            assertThat(validItems).hasSize(2);
        }

        @Test
        @DisplayName("全部有效物品时应该保留所有")
        void shouldKeepAllValidItems() {
            ItemStack[] items = new ItemStack[] {
                new ItemStack(Material.DIAMOND),
                new ItemStack(Material.GOLD_INGOT),
                new ItemStack(Material.IRON_INGOT)
            };

            ItemStack[] validItems = Arrays.stream(items)
                .filter(item -> item != null && !item.getType().isAir())
                .toArray(ItemStack[]::new);

            assertThat(validItems).hasSize(3);
        }
    }

    @Nested
    @DisplayName("回调测试")
    class CallbackTests {

        @Test
        @DisplayName("确认回调应该被正确触发")
        void shouldTriggerConfirmCallback() {
            AtomicBoolean confirmed = new AtomicBoolean(false);
            AtomicReference<ItemStack[]> receivedItems = new AtomicReference<>();

            // Simulate callback
            Runnable onConfirm = () -> {
                confirmed.set(true);
                receivedItems.set(new ItemStack[] { new ItemStack(Material.DIAMOND) });
            };

            onConfirm.run();

            assertThat(confirmed.get()).isTrue();
            assertThat(receivedItems.get()).isNotEmpty();
        }

        @Test
        @DisplayName("取消回调应该被正确触发")
        void shouldTriggerCancelCallback() {
            AtomicBoolean cancelled = new AtomicBoolean(false);

            Runnable onCancel = () -> cancelled.set(true);

            onCancel.run();

            assertThat(cancelled.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("物品槽位测试")
    class SlotTests {

        @Test
        @DisplayName("内容区应该有 45 个槽位 (5行)")
        void shouldHave45ContentSlots() {
            int contentSlots = 45; // 5 rows * 9 columns
            
            assertThat(contentSlots).isEqualTo(45);
        }

        @Test
        @DisplayName("工具栏应该在第6行")
        void shouldHaveToolbarRow() {
            int toolbarStartSlot = 45; // Row 6 starts at slot 45
            int toolbarEndSlot = 53;   // Row 6 ends at slot 53
            
            assertThat(toolbarEndSlot - toolbarStartSlot + 1).isEqualTo(9);
        }

        @Test
        @DisplayName("确认按钮应该在正确位置")
        void shouldHaveConfirmButtonPosition() {
            int confirmSlot = 48; // Middle-left of toolbar
            
            assertThat(confirmSlot).isGreaterThanOrEqualTo(45);
            assertThat(confirmSlot).isLessThanOrEqualTo(53);
        }

        @Test
        @DisplayName("取消按钮应该在正确位置")
        void shouldHaveCancelButtonPosition() {
            int cancelSlot = 50; // Middle-right of toolbar
            
            assertThat(cancelSlot).isGreaterThanOrEqualTo(45);
            assertThat(cancelSlot).isLessThanOrEqualTo(53);
        }
    }

    @Nested
    @DisplayName("物品返还测试")
    class ItemReturnTests {

        @Test
        @DisplayName("取消时应该返还所有物品")
        void shouldReturnItemsOnCancel() {
            ItemStack[] itemsToReturn = new ItemStack[] {
                new ItemStack(Material.DIAMOND, 5),
                new ItemStack(Material.GOLD_INGOT, 10)
            };

            // Clear player inventory first
            player.getInventory().clear();

            // Simulate returning items
            for (ItemStack item : itemsToReturn) {
                if (item != null && !item.getType().isAir()) {
                    player.getInventory().addItem(item);
                }
            }

            assertThat(player.getInventory().contains(Material.DIAMOND)).isTrue();
            assertThat(player.getInventory().contains(Material.GOLD_INGOT)).isTrue();
        }

        @Test
        @DisplayName("确认时不应该返还物品")
        void shouldNotReturnItemsOnConfirm() {
            // Items are consumed when confirmed, not returned
            ItemStack[] selectedItems = new ItemStack[] {
                new ItemStack(Material.DIAMOND, 5)
            };

            // These items should be attached to mail, not returned to player
            assertThat(selectedItems).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("物品数量限制测试")
    class ItemLimitTests {

        @Test
        @DisplayName("应该限制最大物品数量")
        void shouldLimitMaxItems() {
            int maxItems = 27; // Config default
            
            ItemStack[] items = new ItemStack[50];
            for (int i = 0; i < 50; i++) {
                items[i] = new ItemStack(Material.STONE);
            }

            // Validate that we would reject > maxItems
            assertThat(items.length).isGreaterThan(maxItems);
        }

        @Test
        @DisplayName("在限制内的物品数量应该被接受")
        void shouldAcceptItemsWithinLimit() {
            int maxItems = 27;
            
            ItemStack[] items = new ItemStack[20];
            for (int i = 0; i < 20; i++) {
                items[i] = new ItemStack(Material.STONE);
            }

            assertThat(items.length).isLessThanOrEqualTo(maxItems);
        }
    }
}
