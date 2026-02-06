package com.ultikits.plugins.remotebag.entity;

import com.ultikits.plugins.remotebag.enums.LockType;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BagLockInfo Tests")
class BagLockInfoTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void buildAllFields() {
            UUID uuid = UUID.randomUUID();
            long now = System.currentTimeMillis();

            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(uuid)
                    .holderName("TestPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(now)
                    .build();

            assertThat(info.getHolderUuid()).isEqualTo(uuid);
            assertThat(info.getHolderName()).isEqualTo("TestPlayer");
            assertThat(info.getLockType()).isEqualTo(LockType.OWNER);
            assertThat(info.getAcquiredAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Should build with admin lock type")
        void buildAdminLockType() {
            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Admin")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(System.currentTimeMillis())
                    .build();

            assertThat(info.getLockType()).isEqualTo(LockType.ADMIN);
        }
    }

    @Nested
    @DisplayName("isExpired Method")
    class IsExpiredMethod {

        @Test
        @DisplayName("Should return false when not expired")
        void notExpired() {
            long now = System.currentTimeMillis();
            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(now - 100_000) // 100 seconds ago
                    .build();

            assertThat(info.isExpired(300_000)).isFalse(); // 5 min timeout
        }

        @Test
        @DisplayName("Should return true when expired")
        void expired() {
            long now = System.currentTimeMillis();
            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(now - 400_000) // 400 seconds ago
                    .build();

            assertThat(info.isExpired(300_000)).isTrue(); // 5 min timeout
        }

        @Test
        @DisplayName("Should return false when exactly at timeout")
        void exactTimeout() {
            long now = System.currentTimeMillis();
            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(now - 300_000) // exactly 300 seconds ago
                    .build();

            assertThat(info.isExpired(300_000)).isFalse();
        }

        @Test
        @DisplayName("Should handle very recent lock")
        void veryRecentLock() {
            long now = System.currentTimeMillis();
            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(now)
                    .build();

            assertThat(info.isExpired(1000)).isFalse();
        }
    }

    @Nested
    @DisplayName("Getters")
    class Getters {

        @Test
        @DisplayName("Should return all fields via getters")
        void gettersWork() {
            UUID uuid = UUID.randomUUID();
            long timestamp = 12345L;

            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(uuid)
                    .holderName("GetterTest")
                    .lockType(LockType.ADMIN)
                    .acquiredAt(timestamp)
                    .build();

            assertThat(info.getHolderUuid()).isEqualTo(uuid);
            assertThat(info.getHolderName()).isEqualTo("GetterTest");
            assertThat(info.getLockType()).isEqualTo(LockType.ADMIN);
            assertThat(info.getAcquiredAt()).isEqualTo(timestamp);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("Should be equal when same values")
        void equalsSameValues() {
            UUID uuid = UUID.randomUUID();
            long timestamp = 1000L;

            BagLockInfo a = BagLockInfo.builder()
                    .holderUuid(uuid)
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(timestamp)
                    .build();
            BagLockInfo b = BagLockInfo.builder()
                    .holderUuid(uuid)
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(timestamp)
                    .build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when different UUID")
        void notEqualDifferentUuid() {
            BagLockInfo a = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();
            BagLockInfo b = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("Player")
                    .lockType(LockType.OWNER)
                    .acquiredAt(1000L)
                    .build();

            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTest {

        @Test
        @DisplayName("Should implement toString")
        void toStringContainsFields() {
            BagLockInfo info = BagLockInfo.builder()
                    .holderUuid(UUID.randomUUID())
                    .holderName("ToStringPlayer")
                    .lockType(LockType.OWNER)
                    .acquiredAt(12345L)
                    .build();

            String str = info.toString();
            assertThat(str).contains("ToStringPlayer");
            assertThat(str).contains("OWNER");
        }
    }
}
