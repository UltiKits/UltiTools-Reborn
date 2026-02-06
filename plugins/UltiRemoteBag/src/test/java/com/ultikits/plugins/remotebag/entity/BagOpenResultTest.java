package com.ultikits.plugins.remotebag.entity;

import com.ultikits.plugins.remotebag.enums.AccessMode;
import com.ultikits.plugins.remotebag.enums.LockType;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BagOpenResult Tests")
class BagOpenResultTest {

    @Nested
    @DisplayName("Edit Mode Factory")
    class EditModeFactory {

        @Test
        @DisplayName("Should create edit mode result")
        void createsEditMode() {
            BagOpenResult result = BagOpenResult.editMode();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.EDIT);
            assertThat(result.getMessage()).isNull();
            assertThat(result.getExistingLock()).isNull();
        }

        @Test
        @DisplayName("isEditMode should return true")
        void isEditModeTrue() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.isEditMode()).isTrue();
        }

        @Test
        @DisplayName("isReadOnlyMode should return false")
        void isReadOnlyModeFalse() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.isReadOnlyMode()).isFalse();
        }

        @Test
        @DisplayName("Edit mode result should have null existing lock")
        void editModeHasNullLock() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.getExistingLock()).isNull();
        }

        @Test
        @DisplayName("Edit mode result should have null message")
        void editModeHasNullMessage() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result.getMessage()).isNull();
        }
    }

    @Nested
    @DisplayName("Read Only Mode Factory")
    class ReadOnlyModeFactory {

        @Test
        @DisplayName("Should create read only mode result")
        void createsReadOnlyMode() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.READ_ONLY);
            assertThat(result.getMessage()).contains("Owner");
            assertThat(result.getMessage()).contains("只读模式");
            assertThat(result.getExistingLock()).isSameAs(lock);
        }

        @Test
        @DisplayName("isEditMode should return false")
        void isEditModeFalse() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(result.isEditMode()).isFalse();
        }

        @Test
        @DisplayName("isReadOnlyMode should return true")
        void isReadOnlyModeTrue() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(result.isReadOnlyMode()).isTrue();
        }

        @Test
        @DisplayName("Message should contain holder name")
        void messageContainsHolderName() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("SpecificPlayerName")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(result.getMessage()).contains("SpecificPlayerName");
        }

        @Test
        @DisplayName("Message should include color code marker")
        void messageContainsColorCode() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            // Message starts with color code section symbol
            assertThat(result.getMessage()).startsWith("\u00a7");
        }

        @Test
        @DisplayName("Should preserve lock reference")
        void preservesLockReference() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner")
                    .lockType(LockType.OWNER)
                    .acquiredAt(12345L)
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);
            assertThat(result.getExistingLock().getAcquiredAt()).isEqualTo(12345L);
        }
    }

    @Nested
    @DisplayName("Blocked Factory")
    class BlockedFactory {

        @Test
        @DisplayName("Should create blocked result for owner lock")
        void blockedOwnerLock() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("OtherPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getAccessMode()).isNull();
            assertThat(result.getMessage()).contains("OtherPlayer");
            assertThat(result.getMessage()).contains("使用中");
            assertThat(result.getExistingLock()).isSameAs(lock);
        }

        @Test
        @DisplayName("Should create blocked result for admin lock")
        void blockedAdminLock() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("AdminPlayer")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getAccessMode()).isNull();
            assertThat(result.getMessage()).contains("管理员");
            assertThat(result.getMessage()).contains("AdminPlayer");
            assertThat(result.getExistingLock()).isSameAs(lock);
        }

        @Test
        @DisplayName("Admin blocked message should not contain generic usage text")
        void adminBlockedMessageDiffers() {
            BagLockInfo adminLock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Admin1")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagLockInfo ownerLock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Owner1")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult adminResult = BagOpenResult.blocked(adminLock);
            BagOpenResult ownerResult = BagOpenResult.blocked(ownerLock);

            // Admin message should contain "管理员", owner message should not
            assertThat(adminResult.getMessage()).contains("管理员");
            assertThat(ownerResult.getMessage()).doesNotContain("管理员");
        }

        @Test
        @DisplayName("Blocked message should start with red color code")
        void blockedMessageStartsWithRed() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(result.getMessage()).startsWith("\u00a7c");
        }

        @Test
        @DisplayName("isEditMode should return false")
        void isEditModeFalse() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(result.isEditMode()).isFalse();
        }

        @Test
        @DisplayName("isReadOnlyMode should return false")
        void isReadOnlyModeFalse() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(result.isReadOnlyMode()).isFalse();
        }

        @Test
        @DisplayName("Blocked result should have null access mode")
        void blockedHasNullAccessMode() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            assertThat(result.getAccessMode()).isNull();
        }
    }

    @Nested
    @DisplayName("Getters")
    class Getters {

        @Test
        @DisplayName("Should return all fields for read only mode")
        void gettersWorkReadOnly() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Test")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.readOnlyMode(lock);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.READ_ONLY);
            assertThat(result.getMessage()).isNotNull();
            assertThat(result.getExistingLock()).isNotNull();
        }

        @Test
        @DisplayName("Should return all fields for edit mode")
        void gettersWorkEditMode() {
            BagOpenResult result = BagOpenResult.editMode();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAccessMode()).isEqualTo(AccessMode.EDIT);
            assertThat(result.getMessage()).isNull();
            assertThat(result.getExistingLock()).isNull();
        }

        @Test
        @DisplayName("Should return all fields for blocked mode")
        void gettersWorkBlocked() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Blocker")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getAccessMode()).isNull();
            assertThat(result.getMessage()).isNotNull();
            assertThat(result.getExistingLock()).isSameAs(lock);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Two edit mode results should be equal")
        void editModesEqual() {
            BagOpenResult result1 = BagOpenResult.editMode();
            BagOpenResult result2 = BagOpenResult.editMode();

            assertThat(result1).isEqualTo(result2);
            assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
        }

        @Test
        @DisplayName("Edit mode and blocked should not be equal")
        void editModeNotEqualToBlocked() {
            BagOpenResult edit = BagOpenResult.editMode();
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();
            BagOpenResult blocked = BagOpenResult.blocked(lock);

            assertThat(edit).isNotEqualTo(blocked);
        }

        @Test
        @DisplayName("Result should not be equal to null")
        void notEqualToNull() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Result should not be equal to different type")
        void notEqualToDifferentType() {
            BagOpenResult result = BagOpenResult.editMode();
            assertThat(result).isNotEqualTo("not a result");
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("Edit mode toString should contain relevant fields")
        void editModeToString() {
            BagOpenResult result = BagOpenResult.editMode();
            String str = result.toString();

            assertThat(str).contains("success=true");
            assertThat(str).contains("EDIT");
        }

        @Test
        @DisplayName("Blocked mode toString should contain relevant fields")
        void blockedToString() {
            BagLockInfo lock = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            BagOpenResult result = BagOpenResult.blocked(lock);
            String str = result.toString();

            assertThat(str).contains("success=false");
        }
    }
}
