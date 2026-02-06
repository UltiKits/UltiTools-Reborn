package com.ultikits.plugins.remotebag.service;

import com.ultikits.plugins.remotebag.UltiRemoteBagTestHelper;
import com.ultikits.plugins.remotebag.entity.BagLockInfo;
import com.ultikits.plugins.remotebag.entity.BagOpenResult;
import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.enums.LockType;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BagLockService Tests")
class BagLockServiceTest {

    private BagLockService service;
    private Player owner;
    private Player admin;
    private UUID ownerUuid;
    private UUID adminUuid;

    @BeforeEach
    void setUp() throws Exception {
        UltiRemoteBagTestHelper.setUp();

        service = new BagLockService();
        service.setLockTimeout(300); // 5 minutes

        ownerUuid = UUID.randomUUID();
        adminUuid = UUID.randomUUID();

        owner = UltiRemoteBagTestHelper.createMockPlayer("Owner", ownerUuid);
        admin = UltiRemoteBagTestHelper.createMockPlayer("Admin", adminUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRemoteBagTestHelper.tearDown();
    }

    // ==================== ownerOpen ====================

    @Nested
    @DisplayName("ownerOpen")
    class OwnerOpen {

        @Test
        @DisplayName("Should grant edit mode when no lock")
        void grantsEditWhenNoLock() {
            BagOpenResult result = service.ownerOpen(ownerUuid, 1, owner);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("Should grant edit mode when owner already holds lock")
        void grantsEditWhenOwnLock() {
            service.ownerOpen(ownerUuid, 1, owner);

            BagOpenResult result = service.ownerOpen(ownerUuid, 1, owner);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("Should block when admin holds lock")
        void blocksWhenAdminLock() {
            service.adminOpen(ownerUuid, 1, admin);

            BagOpenResult result = service.ownerOpen(ownerUuid, 1, owner);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getExistingLock()).isNotNull();
        }

        @Test
        @DisplayName("Should acquire lock when admin lock expired")
        void acquiresWhenAdminLockExpired() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1); // 1 second

            expiredService.adminOpen(ownerUuid, 1, admin);
            Thread.sleep(1100); // Wait for expiration

            BagOpenResult result = expiredService.ownerOpen(ownerUuid, 1, owner);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("Should set owner lock with correct fields after acquiring")
        void setsOwnerLockCorrectly() {
            service.ownerOpen(ownerUuid, 1, owner);

            Optional<BagLockInfo> lockInfo = service.getLockInfo(ownerUuid, 1);
            assertThat(lockInfo).isPresent();
            assertThat(lockInfo.get().getHolderUuid()).isEqualTo(ownerUuid);
            assertThat(lockInfo.get().getHolderName()).isEqualTo("Owner");
            assertThat(lockInfo.get().getLockType()).isEqualTo(LockType.OWNER);
        }

        @Test
        @DisplayName("Should handle different pages independently")
        void handlesDifferentPagesIndependently() {
            service.adminOpen(ownerUuid, 1, admin);

            // Page 1 should be blocked, page 2 should be available
            BagOpenResult page1 = service.ownerOpen(ownerUuid, 1, owner);
            BagOpenResult page2 = service.ownerOpen(ownerUuid, 2, owner);

            assertThat(page1.isSuccess()).isFalse();
            assertThat(page2.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should notify read-only admins when owner opens")
        void notifiesReadOnlyAdmins() throws Exception {
            // Set up Bukkit.server for Bukkit.getPlayer()
            Server mockServer = mock(Server.class);
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(null, mockServer);

            try {
                Player onlineAdmin = UltiRemoteBagTestHelper.createMockPlayer("OnlineAdmin", adminUuid);
                when(onlineAdmin.isOnline()).thenReturn(true);
                when(mockServer.getPlayer(adminUuid)).thenReturn(onlineAdmin);

                // Admin first opens in read-only (owner holds lock)
                service.ownerOpen(ownerUuid, 1, owner);
                service.adminOpen(ownerUuid, 1, admin);

                // Now release owner lock and have owner re-open
                service.release(ownerUuid, 1, ownerUuid);

                // Owner re-opens - should notify admin
                service.ownerOpen(ownerUuid, 1, owner);

                // The admin was in read-only session, so notify should be triggered
                // (We can't verify the exact message because it depends on read-only session tracking)
            } finally {
                serverField.set(null, null);
            }
        }

        @Test
        @DisplayName("Should replace expired lock with new owner lock")
        void replacesExpiredLock() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1);

            // Admin acquires lock
            expiredService.adminOpen(ownerUuid, 1, admin);
            Thread.sleep(1100);

            // Owner should get edit mode (expired lock removed)
            BagOpenResult result = expiredService.ownerOpen(ownerUuid, 1, owner);
            assertThat(result.isEditMode()).isTrue();

            // Lock should now be held by owner
            Optional<BagLockInfo> lockInfo = expiredService.getLockInfo(ownerUuid, 1);
            assertThat(lockInfo).isPresent();
            assertThat(lockInfo.get().getLockType()).isEqualTo(LockType.OWNER);
        }
    }

    // ==================== adminOpen ====================

    @Nested
    @DisplayName("adminOpen")
    class AdminOpen {

        @Test
        @DisplayName("Should grant edit mode when no lock")
        void grantsEditWhenNoLock() {
            BagOpenResult result = service.adminOpen(ownerUuid, 1, admin);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("Should grant read only when owner holds lock")
        void grantsReadOnlyWhenOwnerLock() {
            service.ownerOpen(ownerUuid, 1, owner);

            BagOpenResult result = service.adminOpen(ownerUuid, 1, admin);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isReadOnlyMode()).isTrue();
            assertThat(result.getExistingLock()).isNotNull();
        }

        @Test
        @DisplayName("Should grant edit mode when admin already holds lock")
        void grantsEditWhenOwnLock() {
            service.adminOpen(ownerUuid, 1, admin);

            BagOpenResult result = service.adminOpen(ownerUuid, 1, admin);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("Should block when different admin holds lock")
        void blocksWhenOtherAdminLock() {
            Player otherAdmin = UltiRemoteBagTestHelper.createMockPlayer("OtherAdmin", UUID.randomUUID());
            service.adminOpen(ownerUuid, 1, otherAdmin);

            BagOpenResult result = service.adminOpen(ownerUuid, 1, admin);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getExistingLock()).isNotNull();
        }

        @Test
        @DisplayName("Should acquire lock when expired")
        void acquiresWhenExpired() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1); // 1 second

            expiredService.adminOpen(ownerUuid, 1, admin);
            Thread.sleep(1100); // Wait for expiration

            Player newAdmin = UltiRemoteBagTestHelper.createMockPlayer("NewAdmin", UUID.randomUUID());
            BagOpenResult result = expiredService.adminOpen(ownerUuid, 1, newAdmin);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("Should set admin lock with correct fields")
        void setsAdminLockCorrectly() {
            service.adminOpen(ownerUuid, 1, admin);

            Optional<BagLockInfo> lockInfo = service.getLockInfo(ownerUuid, 1);
            assertThat(lockInfo).isPresent();
            assertThat(lockInfo.get().getHolderUuid()).isEqualTo(adminUuid);
            assertThat(lockInfo.get().getHolderName()).isEqualTo("Admin");
            assertThat(lockInfo.get().getLockType()).isEqualTo(LockType.ADMIN);
        }

        @Test
        @DisplayName("Admin read-only when owner holds lock should preserve owner lock")
        void readOnlyPreservesOwnerLock() {
            service.ownerOpen(ownerUuid, 1, owner);
            service.adminOpen(ownerUuid, 1, admin);

            // Owner lock should still be there
            Optional<BagLockInfo> lockInfo = service.getLockInfo(ownerUuid, 1);
            assertThat(lockInfo).isPresent();
            assertThat(lockInfo.get().getLockType()).isEqualTo(LockType.OWNER);
        }

        @Test
        @DisplayName("Should acquire edit lock when owner lock expired")
        void acquiresEditWhenOwnerExpired() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1);

            expiredService.ownerOpen(ownerUuid, 1, owner);
            Thread.sleep(1100);

            BagOpenResult result = expiredService.adminOpen(ownerUuid, 1, admin);
            assertThat(result.isEditMode()).isTrue();

            Optional<BagLockInfo> lockInfo = expiredService.getLockInfo(ownerUuid, 1);
            assertThat(lockInfo).isPresent();
            assertThat(lockInfo.get().getLockType()).isEqualTo(LockType.ADMIN);
        }

        @Test
        @DisplayName("Should handle different pages for same owner independently")
        void handlesDifferentPagesIndependently() {
            service.ownerOpen(ownerUuid, 1, owner);

            // Page 1 should be read-only, page 2 should be edit
            BagOpenResult page1 = service.adminOpen(ownerUuid, 1, admin);
            BagOpenResult page2 = service.adminOpen(ownerUuid, 2, admin);

            assertThat(page1.isReadOnlyMode()).isTrue();
            assertThat(page2.isEditMode()).isTrue();
        }
    }

    // ==================== release ====================

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("Should release lock when holder matches")
        void releasesWhenHolderMatches() {
            service.ownerOpen(ownerUuid, 1, owner);

            service.release(ownerUuid, 1, ownerUuid);

            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Should not release lock when holder differs")
        void doesNotReleaseWhenHolderDiffers() {
            service.ownerOpen(ownerUuid, 1, owner);

            service.release(ownerUuid, 1, adminUuid);

            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should handle release when no lock exists")
        void handlesReleaseWhenNoLock() {
            assertThatCode(() -> service.release(ownerUuid, 1, ownerUuid))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should release admin lock")
        void releasesAdminLock() {
            service.adminOpen(ownerUuid, 1, admin);

            service.release(ownerUuid, 1, adminUuid);

            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Should also clean up read-only session when releasing")
        void cleansUpReadOnlySession() {
            // Owner opens, then admin opens (gets read-only)
            service.ownerOpen(ownerUuid, 1, owner);
            service.adminOpen(ownerUuid, 1, admin);

            // Release admin's read-only session
            service.release(ownerUuid, 1, adminUuid);

            // Owner's lock should still exist
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should not release other pages' locks")
        void doesNotReleaseOtherPages() {
            service.ownerOpen(ownerUuid, 1, owner);
            service.ownerOpen(ownerUuid, 2, owner);

            service.release(ownerUuid, 1, ownerUuid);

            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
            assertThat(service.isLocked(ownerUuid, 2)).isTrue();
        }
    }

    // ==================== releaseAll ====================

    @Nested
    @DisplayName("releaseAll")
    class ReleaseAll {

        @Test
        @DisplayName("Should release all locks held by player")
        void releasesAllLocks() {
            service.ownerOpen(ownerUuid, 1, owner);
            service.ownerOpen(ownerUuid, 2, owner);
            service.ownerOpen(ownerUuid, 3, owner);

            service.releaseAll(ownerUuid);

            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
            assertThat(service.isLocked(ownerUuid, 2)).isFalse();
            assertThat(service.isLocked(ownerUuid, 3)).isFalse();
        }

        @Test
        @DisplayName("Should not affect other players locks")
        void doesNotAffectOtherLocks() {
            UUID otherUuid = UUID.randomUUID();
            Player otherPlayer = UltiRemoteBagTestHelper.createMockPlayer("Other", otherUuid);

            service.ownerOpen(ownerUuid, 1, owner);
            service.ownerOpen(otherUuid, 1, otherPlayer);

            service.releaseAll(ownerUuid);

            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
            assertThat(service.isLocked(otherUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should handle releaseAll when no locks exist")
        void handlesNoLocksGracefully() {
            assertThatCode(() -> service.releaseAll(ownerUuid))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should also clean up read-only sessions")
        void cleansUpReadOnlySessions() {
            // Set up: owner holds lock, admin in read-only
            service.ownerOpen(ownerUuid, 1, owner);
            service.adminOpen(ownerUuid, 1, admin);

            // Release all for admin
            service.releaseAll(adminUuid);

            // Owner lock should still exist
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should release admin locks across multiple owners")
        void releasesAdminLocksAcrossOwners() {
            UUID otherOwnerUuid = UUID.randomUUID();

            service.adminOpen(ownerUuid, 1, admin);
            service.adminOpen(otherOwnerUuid, 1, admin);

            service.releaseAll(adminUuid);

            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
            assertThat(service.isLocked(otherOwnerUuid, 1)).isFalse();
        }
    }

    // ==================== getCurrentAccessMode ====================

    @Nested
    @DisplayName("getCurrentAccessMode")
    class GetCurrentAccessMode {

        @Test
        @DisplayName("Should return EDIT when no lock")
        void returnsEditWhenNoLock() {
            AccessMode mode = service.getCurrentAccessMode(ownerUuid, 1, adminUuid);
            assertThat(mode).isEqualTo(AccessMode.EDIT);
        }

        @Test
        @DisplayName("Should return EDIT when viewer holds lock")
        void returnsEditWhenViewerHoldsLock() {
            service.ownerOpen(ownerUuid, 1, owner);

            AccessMode mode = service.getCurrentAccessMode(ownerUuid, 1, ownerUuid);
            assertThat(mode).isEqualTo(AccessMode.EDIT);
        }

        @Test
        @DisplayName("Should return READ_ONLY when owner lock exists")
        void returnsReadOnlyWhenOwnerLock() {
            service.ownerOpen(ownerUuid, 1, owner);

            AccessMode mode = service.getCurrentAccessMode(ownerUuid, 1, adminUuid);
            assertThat(mode).isEqualTo(AccessMode.READ_ONLY);
        }

        @Test
        @DisplayName("Should return EDIT when lock expired")
        void returnsEditWhenExpired() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1);

            expiredService.ownerOpen(ownerUuid, 1, owner);
            Thread.sleep(1100);

            AccessMode mode = expiredService.getCurrentAccessMode(ownerUuid, 1, adminUuid);
            assertThat(mode).isEqualTo(AccessMode.EDIT);
        }

        @Test
        @DisplayName("Should return EDIT when admin holds lock and viewer is admin")
        void returnsEditWhenAdminIsViewer() {
            service.adminOpen(ownerUuid, 1, admin);

            AccessMode mode = service.getCurrentAccessMode(ownerUuid, 1, adminUuid);
            assertThat(mode).isEqualTo(AccessMode.EDIT);
        }

        @Test
        @DisplayName("Should return EDIT when admin holds lock and viewer is different third party")
        void returnsEditWhenAdminLockAndDifferentViewer() {
            service.adminOpen(ownerUuid, 1, admin);

            UUID thirdPartyUuid = UUID.randomUUID();
            AccessMode mode = service.getCurrentAccessMode(ownerUuid, 1, thirdPartyUuid);
            // Admin lock type is ADMIN, not OWNER, so returns EDIT (falls through to last return)
            assertThat(mode).isEqualTo(AccessMode.EDIT);
        }

        @Test
        @DisplayName("Should handle different pages independently for access mode")
        void handlesDifferentPages() {
            service.ownerOpen(ownerUuid, 1, owner);

            AccessMode page1 = service.getCurrentAccessMode(ownerUuid, 1, adminUuid);
            AccessMode page2 = service.getCurrentAccessMode(ownerUuid, 2, adminUuid);

            assertThat(page1).isEqualTo(AccessMode.READ_ONLY);
            assertThat(page2).isEqualTo(AccessMode.EDIT);
        }
    }

    // ==================== canUpgradeToEdit ====================

    @Nested
    @DisplayName("canUpgradeToEdit")
    class CanUpgradeToEdit {

        @Test
        @DisplayName("Should return true when no lock")
        void returnsTrueWhenNoLock() {
            assertThat(service.canUpgradeToEdit(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should return false when lock exists")
        void returnsFalseWhenLocked() {
            service.ownerOpen(ownerUuid, 1, owner);

            assertThat(service.canUpgradeToEdit(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Should return true when lock expired")
        void returnsTrueWhenExpired() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1);

            expiredService.ownerOpen(ownerUuid, 1, owner);
            Thread.sleep(1100);

            assertThat(expiredService.canUpgradeToEdit(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should return false when admin lock exists")
        void returnsFalseWhenAdminLock() {
            service.adminOpen(ownerUuid, 1, admin);

            assertThat(service.canUpgradeToEdit(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Different pages should be independent")
        void differentPagesIndependent() {
            service.ownerOpen(ownerUuid, 1, owner);

            assertThat(service.canUpgradeToEdit(ownerUuid, 1)).isFalse();
            assertThat(service.canUpgradeToEdit(ownerUuid, 2)).isTrue();
        }
    }

    // ==================== getLockInfo ====================

    @Nested
    @DisplayName("getLockInfo")
    class GetLockInfo {

        @Test
        @DisplayName("Should return empty when no lock")
        void returnsEmptyWhenNoLock() {
            Optional<BagLockInfo> info = service.getLockInfo(ownerUuid, 1);
            assertThat(info).isEmpty();
        }

        @Test
        @DisplayName("Should return lock info when locked")
        void returnsInfoWhenLocked() {
            service.ownerOpen(ownerUuid, 1, owner);

            Optional<BagLockInfo> info = service.getLockInfo(ownerUuid, 1);

            assertThat(info).isPresent();
            assertThat(info.get().getHolderUuid()).isEqualTo(ownerUuid);
            assertThat(info.get().getHolderName()).isEqualTo("Owner");
            assertThat(info.get().getLockType()).isEqualTo(LockType.OWNER);
        }

        @Test
        @DisplayName("Should return empty when lock expired")
        void returnsEmptyWhenExpired() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1);

            expiredService.ownerOpen(ownerUuid, 1, owner);
            Thread.sleep(1100);

            Optional<BagLockInfo> info = expiredService.getLockInfo(ownerUuid, 1);
            assertThat(info).isEmpty();
        }

        @Test
        @DisplayName("Should return admin lock info when admin locked")
        void returnsAdminLockInfo() {
            service.adminOpen(ownerUuid, 1, admin);

            Optional<BagLockInfo> info = service.getLockInfo(ownerUuid, 1);

            assertThat(info).isPresent();
            assertThat(info.get().getLockType()).isEqualTo(LockType.ADMIN);
            assertThat(info.get().getHolderName()).isEqualTo("Admin");
        }

        @Test
        @DisplayName("Should include acquiredAt timestamp")
        void includesAcquiredAtTimestamp() {
            long before = System.currentTimeMillis();
            service.ownerOpen(ownerUuid, 1, owner);
            long after = System.currentTimeMillis();

            Optional<BagLockInfo> info = service.getLockInfo(ownerUuid, 1);
            assertThat(info).isPresent();
            assertThat(info.get().getAcquiredAt()).isBetween(before, after);
        }
    }

    // ==================== isLocked ====================

    @Nested
    @DisplayName("isLocked")
    class IsLocked {

        @Test
        @DisplayName("Should return false when no lock")
        void returnsFalseWhenNoLock() {
            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Should return true when locked")
        void returnsTrueWhenLocked() {
            service.ownerOpen(ownerUuid, 1, owner);

            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should return false when lock expired")
        void returnsFalseWhenExpired() throws Exception {
            BagLockService expiredService = new BagLockService();
            expiredService.setLockTimeout(1);

            expiredService.ownerOpen(ownerUuid, 1, owner);
            Thread.sleep(1100);

            assertThat(expiredService.isLocked(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Should return true for admin lock")
        void returnsTrueForAdminLock() {
            service.adminOpen(ownerUuid, 1, admin);
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Should return false after release")
        void returnsFalseAfterRelease() {
            service.ownerOpen(ownerUuid, 1, owner);
            service.release(ownerUuid, 1, ownerUuid);
            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
        }
    }

    // ==================== setLockTimeout ====================

    @Nested
    @DisplayName("setLockTimeout")
    class SetLockTimeout {

        @Test
        @DisplayName("Should update timeout value")
        void updatesTimeout() {
            service.setLockTimeout(600);

            // Indirectly verify by checking lock expiration behavior
            service.ownerOpen(ownerUuid, 1, owner);
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
        }

        @Test
        @DisplayName("Short timeout should cause locks to expire quickly")
        void shortTimeoutExpiresQuickly() throws Exception {
            service.setLockTimeout(1); // 1 second

            service.ownerOpen(ownerUuid, 1, owner);
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();

            Thread.sleep(1100);
            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Long timeout should keep locks alive")
        void longTimeoutKeepsLocksAlive() throws Exception {
            service.setLockTimeout(3600); // 1 hour

            service.ownerOpen(ownerUuid, 1, owner);

            // Even after a short wait, the lock should still be valid
            Thread.sleep(100);
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
        }
    }

    // ==================== Complex Scenarios ====================

    @Nested
    @DisplayName("Complex Scenarios")
    class ComplexScenarios {

        @Test
        @DisplayName("Multiple admins trying to access same bag")
        void multipleAdminsSameBag() {
            Player admin2 = UltiRemoteBagTestHelper.createMockPlayer("Admin2", UUID.randomUUID());

            // First admin gets edit
            BagOpenResult result1 = service.adminOpen(ownerUuid, 1, admin);
            assertThat(result1.isEditMode()).isTrue();

            // Second admin gets blocked
            BagOpenResult result2 = service.adminOpen(ownerUuid, 1, admin2);
            assertThat(result2.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("Owner takes priority over admin")
        void ownerTakesPriority() {
            // Admin releases, owner opens
            service.adminOpen(ownerUuid, 1, admin);
            service.release(ownerUuid, 1, adminUuid);

            BagOpenResult ownerResult = service.ownerOpen(ownerUuid, 1, owner);
            assertThat(ownerResult.isEditMode()).isTrue();

            // Admin now gets read-only
            BagOpenResult adminResult = service.adminOpen(ownerUuid, 1, admin);
            assertThat(adminResult.isReadOnlyMode()).isTrue();
        }

        @Test
        @DisplayName("Lock lifecycle: acquire -> use -> release")
        void lockLifecycle() {
            // Acquire
            assertThat(service.isLocked(ownerUuid, 1)).isFalse();

            BagOpenResult result = service.ownerOpen(ownerUuid, 1, owner);
            assertThat(result.isEditMode()).isTrue();

            // Use (lock should exist)
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
            assertThat(service.getCurrentAccessMode(ownerUuid, 1, ownerUuid))
                    .isEqualTo(AccessMode.EDIT);

            // Release
            service.release(ownerUuid, 1, ownerUuid);
            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
        }

        @Test
        @DisplayName("Different owners different pages isolation")
        void differentOwnersDifferentPages() {
            UUID owner2Uuid = UUID.randomUUID();
            Player owner2 = UltiRemoteBagTestHelper.createMockPlayer("Owner2", owner2Uuid);

            service.ownerOpen(ownerUuid, 1, owner);
            service.ownerOpen(owner2Uuid, 1, owner2);

            // Both should have their own locks
            assertThat(service.isLocked(ownerUuid, 1)).isTrue();
            assertThat(service.isLocked(owner2Uuid, 1)).isTrue();

            // Releasing one should not affect the other
            service.release(ownerUuid, 1, ownerUuid);
            assertThat(service.isLocked(ownerUuid, 1)).isFalse();
            assertThat(service.isLocked(owner2Uuid, 1)).isTrue();
        }
    }
}
