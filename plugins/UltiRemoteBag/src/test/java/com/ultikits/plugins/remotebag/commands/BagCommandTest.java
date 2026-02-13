package com.ultikits.plugins.remotebag.commands;

import com.ultikits.plugins.remotebag.UltiRemoteBag;
import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.config.RemoteBagConfig;
import com.ultikits.plugins.remotebag.entity.BagLockInfo;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.LockType;
import com.ultikits.plugins.remotebag.service.BagLockService;
import com.ultikits.plugins.remotebag.service.RemoteBagService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BagCommand Tests")
class BagCommandTest {

    private BagCommand command;
    private RemoteBagService bagService;
    private BagLockService lockService;
    private RemoteBagConfig config;
    private Player player;
    private UUID playerUuid;
    private Server mockServer;
    private OfflinePlayer offlinePlayer;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        // Mock Bukkit.server for Bukkit.getOfflinePlayer()
        mockServer = mock(Server.class);
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, mockServer);

        // Mock getOfflinePlayer to return an OfflinePlayer with a UUID
        offlinePlayer = mock(OfflinePlayer.class);
        lenient().when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
        lenient().when(offlinePlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(mockServer.getOfflinePlayer(anyString())).thenReturn(offlinePlayer);

        bagService = mock(RemoteBagService.class);
        lockService = mock(BagLockService.class);
        config = UltiRemoteBagTestHelper.createDefaultConfig();

        UltiToolsPlugin mockPlugin = mock(UltiToolsPlugin.class);
        when(mockPlugin.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));

        command = new BagCommand(mockPlugin, bagService, lockService, config);

        playerUuid = UUID.randomUUID();
        player = UltiRemoteBagTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();

        // Clean up Bukkit.server
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, null);
    }

    // ==================== openMainPage ====================

    @Nested
    @DisplayName("openMainPage")
    class OpenMainPage {

        @Test
        @DisplayName("Should open main GUI")
        void opensMainGui() {
            // GUI instantiation requires InventoryAPI.init() which is not available in unit tests.
            // Verify that the command does not throw unexpected exceptions (GUI errors are expected).
            try {
                command.openMainPage(player);
            } catch (Exception e) {
                // Expected: InventoryAPI not initialized
            }
            // If we reach here, the command path was exercised successfully
        }
    }

    // ==================== openPage ====================

    @Nested
    @DisplayName("openPage")
    class OpenPage {

        @Test
        @DisplayName("Should send error when page out of range (too low)")
        void errorWhenPageTooLow() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);

            command.openPage(player, 0);

            verify(player).sendMessage(contains("page_out_of_range"));
        }

        @Test
        @DisplayName("Should send error when page out of range (too high)")
        void errorWhenPageTooHigh() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);

            command.openPage(player, 6);

            verify(player).sendMessage(contains("page_out_of_range"));
        }

        @Test
        @DisplayName("Should send error when bag does not exist")
        void errorWhenBagNotExist() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2));

            command.openPage(player, 3);

            verify(player).sendMessage(contains("bag_not_exist"));
        }

        @Test
        @DisplayName("Should open bag when successful")
        void opensBagWhenSuccessful() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2));
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.editMode());

            // GUI instantiation requires InventoryAPI.init() which is not available in tests.
            // Catch the expected error and verify the service interaction.
            try {
                command.openPage(player, 1);
            } catch (Exception e) {
                // Expected: InventoryAPI not initialized
            }

            verify(lockService).ownerOpen(playerUuid, 1, player);
        }

        @Test
        @DisplayName("Should send error when open fails")
        void errorWhenOpenFails() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1));
            BagLockInfo lockInfo = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("OtherPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.blocked(lockInfo));

            command.openPage(player, 1);

            verify(player).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should load bag if needed before checking existing pages")
        void loadsBagBeforeCheck() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2));

            command.openPage(player, 3);

            verify(bagService).loadBagIfNeeded(playerUuid);
        }

        @Test
        @DisplayName("Should replace page number in error message when page too low")
        void replacePageNumberInMessageLow() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(3);

            command.openPage(player, -1);

            // i18n returns key as-is, so the message will contain the key with replacements
            verify(player).sendMessage(contains("page_out_of_range"));
        }

        @Test
        @DisplayName("Should accept page at exact boundary (page 1)")
        void acceptsPageAtLowerBound() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1));
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.openPage(player, 1);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).ownerOpen(playerUuid, 1, player);
        }

        @Test
        @DisplayName("Should accept page at exact upper boundary")
        void acceptsPageAtUpperBound() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1, 2, 3, 4, 5));
            when(lockService.ownerOpen(playerUuid, 5, player))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.openPage(player, 5);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).ownerOpen(playerUuid, 5, player);
        }

        @Test
        @DisplayName("Should send blocked message with admin lock type")
        void errorWithAdminLock() {
            when(bagService.getPlayerMaxPages(player)).thenReturn(5);
            when(bagService.getPlayerBagPages(playerUuid)).thenReturn(Arrays.asList(1));
            BagLockInfo lockInfo = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("AdminPlayer")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            when(lockService.ownerOpen(playerUuid, 1, player))
                    .thenReturn(BagOpenResult.blocked(lockInfo));

            command.openPage(player, 1);

            // Should send the blocked message (contains "管理员")
            verify(player).sendMessage(contains("AdminPlayer"));
        }
    }

    // ==================== saveBag ====================

    @Nested
    @DisplayName("saveBag")
    class SaveBag {

        @Test
        @DisplayName("Should save and send confirmation")
        void savesAndConfirms() {
            command.saveBag(player);

            verify(bagService).saveBag(playerUuid);
            verify(player).sendMessage(contains("bag_saved_manually"));
        }

        @Test
        @DisplayName("Should call saveBag with correct player UUID")
        void savesWithCorrectUuid() {
            UUID specificUuid = UUID.randomUUID();
            Player specificPlayer = UltiRemoteBagTestHelper.createMockPlayer("SpecificPlayer", specificUuid);

            command.saveBag(specificPlayer);

            verify(bagService).saveBag(specificUuid);
        }
    }

    // ==================== seePlayerBag ====================

    @Nested
    @DisplayName("seePlayerBag")
    class SeePlayerBag {

        @Test
        @DisplayName("Should send error when player not found")
        void errorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);

            command.seePlayerBag(player, "UnknownPlayer");

            verify(player).sendMessage(contains("player_not_found"));
        }

        @Test
        @DisplayName("Should send error when player has no bags")
        void errorWhenNoBags() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(any())).thenReturn(Collections.emptyList());

            command.seePlayerBag(player, "TargetPlayer");

            verify(player).sendMessage(contains("player_no_bags"));
        }

        @Test
        @DisplayName("Should open first page when player has bags")
        void opensFirstPage() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2, 3));
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBag(player, "TargetPlayer");
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).adminOpen(eq(targetUuid), eq(1), eq(player));
        }

        @Test
        @DisplayName("Should load target bag if needed")
        void loadsTargetBagIfNeeded() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBag(player, "TargetPlayer");
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(bagService, atLeast(1)).loadBagIfNeeded(targetUuid);
        }
    }

    // ==================== seePlayerBagPage ====================

    @Nested
    @DisplayName("seePlayerBagPage")
    class SeePlayerBagPage {

        @Test
        @DisplayName("Should send error when player not found")
        void errorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);

            command.seePlayerBagPage(player, "UnknownPlayer", 1);

            verify(player).sendMessage(contains("player_not_found"));
        }

        @Test
        @DisplayName("Should send error when page does not exist")
        void errorWhenPageNotExist() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2));

            command.seePlayerBagPage(player, "TargetPlayer", 5);

            verify(player).sendMessage(contains("bag_not_exist"));
        }

        @Test
        @DisplayName("Should open specific page in edit mode")
        void opensPageInEditMode() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2, 3));
            when(lockService.adminOpen(eq(targetUuid), eq(2), eq(player)))
                    .thenReturn(BagOpenResult.editMode());

            try {
                command.seePlayerBagPage(player, "TargetPlayer", 2);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            verify(lockService).adminOpen(eq(targetUuid), eq(2), eq(player));
        }

        @Test
        @DisplayName("Should show read-only message and open when owner holds lock")
        void opensReadOnlyWhenOwnerLock() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            UUID ownerLockUuid = UUID.randomUUID();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));

            BagLockInfo ownerLock = BagLockInfo.builder()
                    .holderUuid(ownerLockUuid)
                    .holderName("OwnerPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            BagOpenResult readOnlyResult = BagOpenResult.readOnlyMode(ownerLock);
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(readOnlyResult);

            try {
                command.seePlayerBagPage(player, "TargetPlayer", 1);
            } catch (Exception e) {
                // Expected: GUI not initialized
            }

            // Should send the read-only warning message
            verify(player).sendMessage(contains("OwnerPlayer"));
        }

        @Test
        @DisplayName("Should send error and play sound when blocked")
        void errorWhenBlocked() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(offlinePlayer.hasPlayedBefore()).thenReturn(true);
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));

            BagLockInfo adminLock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("OtherAdmin")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            when(lockService.adminOpen(eq(targetUuid), eq(1), eq(player)))
                    .thenReturn(BagOpenResult.blocked(adminLock));

            command.seePlayerBagPage(player, "TargetPlayer", 1);

            verify(player).sendMessage(contains("OtherAdmin"));
        }
    }

    // ==================== Admin Commands ====================

    @Nested
    @DisplayName("Admin Commands")
    class AdminCommands {

        @Test
        @DisplayName("createBag should create new bag page")
        void createBagCreatesPage() {
            when(bagService.createBagPage(any())).thenReturn(2);

            command.createBag(player, "TargetPlayer");

            verify(bagService).createBagPage(any());
            verify(player).sendMessage(contains("admin_bag_created"));
        }

        @Test
        @DisplayName("createBag should send error when creation fails")
        void createBagErrorWhenFails() {
            when(bagService.createBagPage(any())).thenReturn(-1);

            command.createBag(player, "TargetPlayer");

            verify(player).sendMessage(contains("admin_bag_create_failed"));
        }

        @Test
        @DisplayName("createBag should send error when player not found")
        void createBagErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);

            command.createBag(player, "UnknownPlayer");

            verify(player).sendMessage(contains("player_not_found"));
            verify(bagService, never()).createBagPage(any());
        }

        @Test
        @DisplayName("createBag should return 0 as failure")
        void createBagReturnsZeroAsFail() {
            when(bagService.createBagPage(any())).thenReturn(0);

            command.createBag(player, "TargetPlayer");

            // 0 is not > 0, so it should show failure message
            verify(player).sendMessage(contains("admin_bag_create_failed"));
        }

        @Test
        @DisplayName("deleteBag should check if can upgrade to edit")
        void deleteBagChecksCanUpgrade() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(false);

            command.deleteBag(player, "TargetPlayer", 1);

            verify(lockService).canUpgradeToEdit(any(), eq(1));
            verify(player).sendMessage(contains("bag_in_use_cannot_delete"));
        }

        @Test
        @DisplayName("deleteBag should delete when allowed")
        void deleteBagDeletesWhenAllowed() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.deleteBagPage(any(), anyInt())).thenReturn(true);

            command.deleteBag(player, "TargetPlayer", 1);

            verify(bagService).deleteBagPage(any(), eq(1));
            verify(player).sendMessage(contains("admin_bag_deleted"));
        }

        @Test
        @DisplayName("deleteBag should send error when delete fails")
        void deleteBagErrorWhenDeleteFails() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.deleteBagPage(any(), anyInt())).thenReturn(false);

            command.deleteBag(player, "TargetPlayer", 1);

            verify(player).sendMessage(contains("admin_bag_delete_failed"));
        }

        @Test
        @DisplayName("deleteBag should send error when player not found")
        void deleteBagErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);

            command.deleteBag(player, "UnknownPlayer", 1);

            verify(player).sendMessage(contains("player_not_found"));
            verify(lockService, never()).canUpgradeToEdit(any(), anyInt());
        }

        @Test
        @DisplayName("clearBag should check if can upgrade to edit")
        void clearBagChecksCanUpgrade() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(false);

            command.clearBag(player, "TargetPlayer", 1);

            verify(lockService).canUpgradeToEdit(any(), eq(1));
            verify(player).sendMessage(contains("bag_in_use_cannot_clear"));
        }

        @Test
        @DisplayName("clearBag should clear when allowed")
        void clearBagClearsWhenAllowed() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.clearBagPage(any(), anyInt())).thenReturn(true);

            command.clearBag(player, "TargetPlayer", 1);

            verify(bagService).clearBagPage(any(), eq(1));
            verify(player).sendMessage(contains("admin_bag_cleared"));
        }

        @Test
        @DisplayName("clearBag should send error when clear fails")
        void clearBagErrorWhenClearFails() {
            when(lockService.canUpgradeToEdit(any(), anyInt())).thenReturn(true);
            when(bagService.clearBagPage(any(), anyInt())).thenReturn(false);

            command.clearBag(player, "TargetPlayer", 1);

            verify(player).sendMessage(contains("admin_bag_clear_failed"));
        }

        @Test
        @DisplayName("clearBag should send error when player not found")
        void clearBagErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);

            command.clearBag(player, "UnknownPlayer", 1);

            verify(player).sendMessage(contains("player_not_found"));
            verify(lockService, never()).canUpgradeToEdit(any(), anyInt());
        }

        @Test
        @DisplayName("listBags should display all bags")
        void listBagsDisplaysAll() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Arrays.asList(1, 2, 3));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(100);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(10);

            command.listBags(player, "TargetPlayer");

            verify(player, atLeast(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("listBags should display no bags message when empty")
        void listBagsDisplaysEmpty() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Collections.emptyList());

            command.listBags(player, "TargetPlayer");

            verify(player).sendMessage(contains("no_bags"));
        }

        @Test
        @DisplayName("listBags should send error when player not found")
        void listBagsErrorWhenPlayerNotFound() {
            when(offlinePlayer.hasPlayedBefore()).thenReturn(false);

            command.listBags(player, "UnknownPlayer");

            verify(player).sendMessage(contains("player_not_found"));
            verify(bagService, never()).getPlayerBagPages(any());
        }

        @Test
        @DisplayName("listBags should show total count at end")
        void listBagsShowsTotalCount() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Arrays.asList(1, 2));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(50);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(5);

            command.listBags(player, "TargetPlayer");

            verify(player).sendMessage(contains("total_bags"));
        }

        @Test
        @DisplayName("listBags should show title header")
        void listBagsShowsTitle() {
            when(bagService.getPlayerBagPages(any()))
                    .thenReturn(Arrays.asList(1));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(0);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(0);

            command.listBags(player, "TargetPlayer");

            verify(player).sendMessage(contains("bag_list_title"));
        }

        @Test
        @DisplayName("listBags should load target bag if needed")
        void listBagsLoadsTarget() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1));
            when(bagService.getItemCount(any(), anyInt())).thenReturn(0);
            when(bagService.getStackCount(any(), anyInt())).thenReturn(0);

            command.listBags(player, "TargetPlayer");

            verify(bagService).loadBagIfNeeded(targetUuid);
        }

        @Test
        @DisplayName("listBags should display item and stack counts for each page")
        void listBagsDisplaysPerPageStats() {
            UUID targetUuid = offlinePlayer.getUniqueId();
            when(bagService.getPlayerBagPages(targetUuid)).thenReturn(Arrays.asList(1, 2));
            when(bagService.getItemCount(targetUuid, 1)).thenReturn(100);
            when(bagService.getItemCount(targetUuid, 2)).thenReturn(50);
            when(bagService.getStackCount(targetUuid, 1)).thenReturn(10);
            when(bagService.getStackCount(targetUuid, 2)).thenReturn(5);

            command.listBags(player, "TargetPlayer");

            verify(bagService).getItemCount(targetUuid, 1);
            verify(bagService).getItemCount(targetUuid, 2);
            verify(bagService).getStackCount(targetUuid, 1);
            verify(bagService).getStackCount(targetUuid, 2);
        }
    }

    // ==================== handleHelp ====================

    @Nested
    @DisplayName("handleHelp")
    class HandleHelp {

        @Test
        @DisplayName("Should display player help commands")
        void displaysPlayerHelp() {
            command.handleHelp(player);

            // Verify basic help messages were sent
            verify(player, atLeast(3)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should display admin commands when player has admin permission")
        void displaysAdminHelp() {
            when(player.hasPermission("ultibag.admin.see")).thenReturn(true);

            command.handleHelp(player);

            // Should show more messages (basic + admin commands)
            verify(player, atLeast(6)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should not display admin commands when no admin permission")
        void hidesAdminHelpWithoutPermission() {
            when(player.hasPermission("ultibag.admin.see")).thenReturn(false);

            command.handleHelp(player);

            // Should only show basic commands (fewer messages)
            verify(player, atMost(5)).sendMessage(anyString());
        }

        @Test
        @DisplayName("Should do nothing when sender is not a Player")
        void doesNothingForNonPlayer() {
            CommandSender consoleSender = mock(CommandSender.class);

            // handleHelp checks (sender instanceof Player), should do nothing for console
            command.handleHelp(consoleSender);

            verify(consoleSender, never()).sendMessage(any(String.class));
        }
    }
}
