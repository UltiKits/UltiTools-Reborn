package com.ultikits.plugins.social.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BlacklistData Entity Tests")
class BlacklistDataTest {

    @Nested
    @DisplayName("Builder Pattern")
    class BuilderPattern {

        @Test
        @DisplayName("Should build entity with all fields")
        void buildWithAllFields() {
            UUID playerUuid = UUID.randomUUID();
            UUID blockedUuid = UUID.randomUUID();
            long timestamp = System.currentTimeMillis();

            BlacklistData data = BlacklistData.builder()
                    .playerUuid(playerUuid.toString())
                    .blockedUuid(blockedUuid.toString())
                    .blockedName("BlockedPlayer")
                    .createdTime(timestamp)
                    .reason("Harassment")
                    .build();

            assertThat(data.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(data.getBlockedUuid()).isEqualTo(blockedUuid.toString());
            assertThat(data.getBlockedName()).isEqualTo("BlockedPlayer");
            assertThat(data.getCreatedTime()).isEqualTo(timestamp);
            assertThat(data.getReason()).isEqualTo("Harassment");
        }

        @Test
        @DisplayName("Should build entity with minimal fields")
        void buildWithMinimalFields() {
            BlacklistData data = BlacklistData.builder()
                    .playerUuid("player-uuid")
                    .blockedUuid("blocked-uuid")
                    .blockedName("Blocked")
                    .build();

            assertThat(data.getPlayerUuid()).isEqualTo("player-uuid");
            assertThat(data.getBlockedUuid()).isEqualTo("blocked-uuid");
            assertThat(data.getBlockedName()).isEqualTo("Blocked");
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethods {

        @Test
        @DisplayName("Should create blacklist entry without reason")
        void createWithoutReason() {
            UUID playerUuid = UUID.randomUUID();
            UUID blockedUuid = UUID.randomUUID();
            long beforeCreate = System.currentTimeMillis();

            BlacklistData data = BlacklistData.create(playerUuid, blockedUuid, "BlockedPlayer");

            long afterCreate = System.currentTimeMillis();

            assertThat(data.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(data.getBlockedUuid()).isEqualTo(blockedUuid.toString());
            assertThat(data.getBlockedName()).isEqualTo("BlockedPlayer");
            assertThat(data.getCreatedTime()).isBetween(beforeCreate, afterCreate);
            assertThat(data.getReason()).isNull();
        }

        @Test
        @DisplayName("Should create blacklist entry with reason")
        void createWithReason() {
            UUID playerUuid = UUID.randomUUID();
            UUID blockedUuid = UUID.randomUUID();
            long beforeCreate = System.currentTimeMillis();

            BlacklistData data = BlacklistData.create(
                    playerUuid,
                    blockedUuid,
                    "BlockedPlayer",
                    "Spamming"
            );

            long afterCreate = System.currentTimeMillis();

            assertThat(data.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(data.getBlockedUuid()).isEqualTo(blockedUuid.toString());
            assertThat(data.getBlockedName()).isEqualTo("BlockedPlayer");
            assertThat(data.getCreatedTime()).isBetween(beforeCreate, afterCreate);
            assertThat(data.getReason()).isEqualTo("Spamming");
        }
    }

    @Nested
    @DisplayName("Setters and Getters")
    class SettersAndGetters {

        @Test
        @DisplayName("Should update player UUID")
        void updatePlayerUuid() {
            BlacklistData data = BlacklistData.builder()
                    .playerUuid("old-uuid")
                    .build();

            data.setPlayerUuid("new-uuid");

            assertThat(data.getPlayerUuid()).isEqualTo("new-uuid");
        }

        @Test
        @DisplayName("Should update blocked UUID")
        void updateBlockedUuid() {
            BlacklistData data = BlacklistData.builder()
                    .blockedUuid("old-uuid")
                    .build();

            data.setBlockedUuid("new-uuid");

            assertThat(data.getBlockedUuid()).isEqualTo("new-uuid");
        }

        @Test
        @DisplayName("Should update blocked name")
        void updateBlockedName() {
            BlacklistData data = BlacklistData.builder()
                    .blockedName("OldName")
                    .build();

            data.setBlockedName("NewName");

            assertThat(data.getBlockedName()).isEqualTo("NewName");
        }

        @Test
        @DisplayName("Should update reason")
        void updateReason() {
            BlacklistData data = BlacklistData.builder().build();

            data.setReason("Updated reason");

            assertThat(data.getReason()).isEqualTo("Updated reason");
        }

        @Test
        @DisplayName("Should update created time")
        void updateCreatedTime() {
            BlacklistData data = BlacklistData.builder()
                    .createdTime(1000L)
                    .build();

            data.setCreatedTime(2000L);

            assertThat(data.getCreatedTime()).isEqualTo(2000L);
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("Should be equal with same data")
        void equalWithSameData() {
            BlacklistData data1 = BlacklistData.builder()
                    .playerUuid("uuid1")
                    .blockedUuid("uuid2")
                    .blockedName("Blocked")
                    .createdTime(1000L)
                    .reason("Test")
                    .build();

            BlacklistData data2 = BlacklistData.builder()
                    .playerUuid("uuid1")
                    .blockedUuid("uuid2")
                    .blockedName("Blocked")
                    .createdTime(1000L)
                    .reason("Test")
                    .build();

            assertThat(data1).isEqualTo(data2);
        }

        @Test
        @DisplayName("Should not be equal with different data")
        void notEqualWithDifferentData() {
            BlacklistData data1 = BlacklistData.builder()
                    .playerUuid("uuid1")
                    .blockedUuid("uuid2")
                    .build();

            BlacklistData data2 = BlacklistData.builder()
                    .playerUuid("uuid1")
                    .blockedUuid("uuid3")
                    .build();

            assertThat(data1).isNotEqualTo(data2);
        }
    }
}
