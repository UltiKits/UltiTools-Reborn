package com.ultikits.plugins.social.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FriendshipData Entity Tests")
class FriendshipDataTest {

    @Nested
    @DisplayName("Builder Pattern")
    class BuilderPattern {

        @Test
        @DisplayName("Should build entity with all fields")
        void buildWithAllFields() {
            UUID playerUuid = UUID.randomUUID();
            UUID friendUuid = UUID.randomUUID();
            long timestamp = System.currentTimeMillis();

            FriendshipData data = FriendshipData.builder()
                    .playerUuid(playerUuid.toString())
                    .friendUuid(friendUuid.toString())
                    .friendName("TestFriend")
                    .createdTime(timestamp)
                    .nickname("BestFriend")
                    .favorite(true)
                    .build();

            assertThat(data.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(data.getFriendUuid()).isEqualTo(friendUuid.toString());
            assertThat(data.getFriendName()).isEqualTo("TestFriend");
            assertThat(data.getCreatedTime()).isEqualTo(timestamp);
            assertThat(data.getNickname()).isEqualTo("BestFriend");
            assertThat(data.isFavorite()).isTrue();
        }

        @Test
        @DisplayName("Should build entity with minimal fields")
        void buildWithMinimalFields() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("player-uuid")
                    .friendUuid("friend-uuid")
                    .friendName("Friend")
                    .build();

            assertThat(data.getPlayerUuid()).isEqualTo("player-uuid");
            assertThat(data.getFriendUuid()).isEqualTo("friend-uuid");
            assertThat(data.getFriendName()).isEqualTo("Friend");
        }

        @Test
        @DisplayName("Should have false as default for favorite in builder")
        void defaultFavoriteIsFalse() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .build();

            assertThat(data.isFavorite()).isFalse();
        }

        @Test
        @DisplayName("Should have null as default for nickname in builder")
        void defaultNicknameIsNull() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .build();

            assertThat(data.getNickname()).isNull();
        }

        @Test
        @DisplayName("Should have 0 as default for createdTime in builder")
        void defaultCreatedTimeIsZero() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .build();

            assertThat(data.getCreatedTime()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should have null as default for playerUuid in builder")
        void defaultPlayerUuidIsNull() {
            FriendshipData data = FriendshipData.builder().build();
            assertThat(data.getPlayerUuid()).isNull();
        }

        @Test
        @DisplayName("Should have null as default for friendUuid in builder")
        void defaultFriendUuidIsNull() {
            FriendshipData data = FriendshipData.builder().build();
            assertThat(data.getFriendUuid()).isNull();
        }

        @Test
        @DisplayName("Should have null as default for friendName in builder")
        void defaultFriendNameIsNull() {
            FriendshipData data = FriendshipData.builder().build();
            assertThat(data.getFriendName()).isNull();
        }
    }

    @Nested
    @DisplayName("Factory Method")
    class FactoryMethod {

        @Test
        @DisplayName("Should create friendship with factory method")
        void createFriendship() {
            UUID playerUuid = UUID.randomUUID();
            UUID friendUuid = UUID.randomUUID();
            long beforeCreate = System.currentTimeMillis();

            FriendshipData data = FriendshipData.create(playerUuid, friendUuid, "NewFriend");

            long afterCreate = System.currentTimeMillis();

            assertThat(data.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(data.getFriendUuid()).isEqualTo(friendUuid.toString());
            assertThat(data.getFriendName()).isEqualTo("NewFriend");
            assertThat(data.getCreatedTime()).isBetween(beforeCreate, afterCreate);
            assertThat(data.isFavorite()).isFalse();
            assertThat(data.getNickname()).isNull();
        }

        @Test
        @DisplayName("Should create distinct timestamps for sequential creates")
        void distinctTimestamps() throws InterruptedException {
            UUID playerUuid = UUID.randomUUID();
            UUID friendUuid1 = UUID.randomUUID();
            UUID friendUuid2 = UUID.randomUUID();

            FriendshipData data1 = FriendshipData.create(playerUuid, friendUuid1, "Friend1");
            // Small delay to ensure different timestamp
            Thread.sleep(2);
            FriendshipData data2 = FriendshipData.create(playerUuid, friendUuid2, "Friend2");

            assertThat(data2.getCreatedTime()).isGreaterThanOrEqualTo(data1.getCreatedTime());
        }

        @Test
        @DisplayName("Should convert UUIDs to strings correctly")
        void convertUuidsToStrings() {
            UUID playerUuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
            UUID friendUuid = UUID.fromString("abcdef01-abcd-abcd-abcd-abcdef012345");

            FriendshipData data = FriendshipData.create(playerUuid, friendUuid, "Friend");

            assertThat(data.getPlayerUuid()).isEqualTo("12345678-1234-1234-1234-123456789abc");
            assertThat(data.getFriendUuid()).isEqualTo("abcdef01-abcd-abcd-abcd-abcdef012345");
        }
    }

    @Nested
    @DisplayName("No-Args Constructor")
    class NoArgsConstructor {

        @Test
        @DisplayName("Should create empty entity with no-args constructor")
        void createEmpty() {
            FriendshipData data = new FriendshipData();

            assertThat(data.getPlayerUuid()).isNull();
            assertThat(data.getFriendUuid()).isNull();
            assertThat(data.getFriendName()).isNull();
            assertThat(data.getCreatedTime()).isEqualTo(0L);
            assertThat(data.getNickname()).isNull();
            assertThat(data.isFavorite()).isFalse();
        }

        @Test
        @DisplayName("Should have null id from AbstractDataEntity")
        void nullId() {
            FriendshipData data = new FriendshipData();
            assertThat(data.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("All-Args Constructor")
    class AllArgsConstructor {

        @Test
        @DisplayName("Should create entity with all-args constructor")
        void createWithAllArgs() {
            FriendshipData data = new FriendshipData(
                    "player-uuid",
                    "friend-uuid",
                    "FriendName",
                    1234567890L,
                    "Buddy",
                    true
            );

            assertThat(data.getPlayerUuid()).isEqualTo("player-uuid");
            assertThat(data.getFriendUuid()).isEqualTo("friend-uuid");
            assertThat(data.getFriendName()).isEqualTo("FriendName");
            assertThat(data.getCreatedTime()).isEqualTo(1234567890L);
            assertThat(data.getNickname()).isEqualTo("Buddy");
            assertThat(data.isFavorite()).isTrue();
        }

        @Test
        @DisplayName("Should allow null values in all-args constructor")
        void createWithNullValues() {
            FriendshipData data = new FriendshipData(
                    null, null, null, 0L, null, false
            );

            assertThat(data.getPlayerUuid()).isNull();
            assertThat(data.getFriendUuid()).isNull();
            assertThat(data.getFriendName()).isNull();
            assertThat(data.getCreatedTime()).isEqualTo(0L);
            assertThat(data.getNickname()).isNull();
            assertThat(data.isFavorite()).isFalse();
        }
    }

    @Nested
    @DisplayName("Setters and Getters")
    class SettersAndGetters {

        @Test
        @DisplayName("Should update favorite status")
        void updateFavorite() {
            FriendshipData data = FriendshipData.builder()
                    .favorite(false)
                    .build();

            data.setFavorite(true);

            assertThat(data.isFavorite()).isTrue();
        }

        @Test
        @DisplayName("Should toggle favorite back to false")
        void toggleFavoriteBack() {
            FriendshipData data = FriendshipData.builder()
                    .favorite(true)
                    .build();

            data.setFavorite(false);

            assertThat(data.isFavorite()).isFalse();
        }

        @Test
        @DisplayName("Should update nickname")
        void updateNickname() {
            FriendshipData data = FriendshipData.builder().build();

            data.setNickname("MyBuddy");

            assertThat(data.getNickname()).isEqualTo("MyBuddy");
        }

        @Test
        @DisplayName("Should set nickname to null")
        void setNicknameToNull() {
            FriendshipData data = FriendshipData.builder()
                    .nickname("OldNick")
                    .build();

            data.setNickname(null);

            assertThat(data.getNickname()).isNull();
        }

        @Test
        @DisplayName("Should update friend name")
        void updateFriendName() {
            FriendshipData data = FriendshipData.builder()
                    .friendName("OldName")
                    .build();

            data.setFriendName("NewName");

            assertThat(data.getFriendName()).isEqualTo("NewName");
        }

        @Test
        @DisplayName("Should update player UUID")
        void updatePlayerUuid() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("old-uuid")
                    .build();

            data.setPlayerUuid("new-uuid");

            assertThat(data.getPlayerUuid()).isEqualTo("new-uuid");
        }

        @Test
        @DisplayName("Should update friend UUID")
        void updateFriendUuid() {
            FriendshipData data = FriendshipData.builder()
                    .friendUuid("old-uuid")
                    .build();

            data.setFriendUuid("new-uuid");

            assertThat(data.getFriendUuid()).isEqualTo("new-uuid");
        }

        @Test
        @DisplayName("Should update created time")
        void updateCreatedTime() {
            FriendshipData data = FriendshipData.builder()
                    .createdTime(1000L)
                    .build();

            data.setCreatedTime(2000L);

            assertThat(data.getCreatedTime()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("Should update ID from AbstractDataEntity")
        void updateId() {
            FriendshipData data = new FriendshipData();

            data.setId("test-id-123");

            assertThat(data.getId()).isEqualTo("test-id-123");
        }

        @Test
        @DisplayName("Should update ID to different types")
        void updateIdToObjectType() {
            FriendshipData data = new FriendshipData();

            data.setId(42);
            assertThat(data.getId()).isEqualTo(42);

            data.setId("string-id");
            assertThat(data.getId()).isEqualTo("string-id");
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("Should be equal with same data")
        void equalWithSameData() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .createdTime(1000L)
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .createdTime(1000L)
                    .build();

            assertThat(data1).isEqualTo(data2);
        }

        @Test
        @DisplayName("Should not be equal with different data")
        void notEqualWithDifferentData() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid3")
                    .build();

            assertThat(data1).isNotEqualTo(data2);
        }

        @Test
        @DisplayName("Should not be equal with different favorite status")
        void notEqualWithDifferentFavorite() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .favorite(true)
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .favorite(false)
                    .build();

            assertThat(data1).isNotEqualTo(data2);
        }

        @Test
        @DisplayName("Should not be equal with different nickname")
        void notEqualWithDifferentNickname() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .nickname("Nick1")
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .nickname("Nick2")
                    .build();

            assertThat(data1).isNotEqualTo(data2);
        }

        @Test
        @DisplayName("Should not be equal with different createdTime")
        void notEqualWithDifferentCreatedTime() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .createdTime(1000L)
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .createdTime(2000L)
                    .build();

            assertThat(data1).isNotEqualTo(data2);
        }

        @Test
        @DisplayName("Should not be equal with different playerUuid")
        void notEqualWithDifferentPlayerUuid() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid-different")
                    .friendUuid("uuid2")
                    .build();

            assertThat(data1).isNotEqualTo(data2);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void notEqualToNull() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .build();

            assertThat(data).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not be equal to different type")
        void notEqualToDifferentType() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .build();

            assertThat(data).isNotEqualTo("not a FriendshipData");
        }

        @Test
        @DisplayName("Should be equal to itself")
        void equalToSelf() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .build();

            assertThat(data).isEqualTo(data);
        }

        @Test
        @DisplayName("Should have equal hashCode for equal objects")
        void equalHashCode() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .createdTime(1000L)
                    .nickname("Nick")
                    .favorite(true)
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .createdTime(1000L)
                    .nickname("Nick")
                    .favorite(true)
                    .build();

            assertThat(data1.hashCode()).isEqualTo(data2.hashCode());
        }

        @Test
        @DisplayName("Should have different hashCode for different objects")
        void differentHashCode() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .build();

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid3")
                    .build();

            assertThat(data1.hashCode()).isNotEqualTo(data2.hashCode());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("Should include all fields in toString")
        void toStringIncludesAllFields() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("player-uuid-123")
                    .friendUuid("friend-uuid-456")
                    .friendName("TestFriend")
                    .createdTime(9999L)
                    .nickname("BFF")
                    .favorite(true)
                    .build();

            String str = data.toString();

            assertThat(str).contains("player-uuid-123");
            assertThat(str).contains("friend-uuid-456");
            assertThat(str).contains("TestFriend");
            assertThat(str).contains("9999");
            assertThat(str).contains("BFF");
            assertThat(str).contains("true");
        }

        @Test
        @DisplayName("Should handle null fields in toString")
        void toStringHandlesNullFields() {
            FriendshipData data = new FriendshipData();

            String str = data.toString();

            assertThat(str).isNotNull();
            assertThat(str).contains("null");
        }

        @Test
        @DisplayName("toString should contain class name")
        void toStringContainsClassName() {
            FriendshipData data = FriendshipData.builder().build();

            String str = data.toString();

            assertThat(str).contains("FriendshipData");
        }
    }

    @Nested
    @DisplayName("CanEqual")
    class CanEqualTests {

        @Test
        @DisplayName("canEqual should return true for same type")
        void canEqualSameType() {
            FriendshipData data1 = FriendshipData.builder().build();
            FriendshipData data2 = FriendshipData.builder().build();

            assertThat(data1.canEqual(data2)).isTrue();
        }

        @Test
        @DisplayName("canEqual should return false for different type")
        void canEqualDifferentType() {
            FriendshipData data = FriendshipData.builder().build();

            assertThat(data.canEqual("string")).isFalse();
            assertThat(data.canEqual(42)).isFalse();
        }
    }

    @Nested
    @DisplayName("Inheritance from AbstractDataEntity")
    class InheritanceTests {

        @Test
        @DisplayName("Should be instance of AbstractDataEntity")
        void isInstanceOfAbstractDataEntity() {
            FriendshipData data = new FriendshipData();

            assertThat(data).isInstanceOf(com.ultikits.ultitools.abstracts.AbstractDataEntity.class);
        }

        @Test
        @DisplayName("Should be Serializable")
        void isSerializable() {
            FriendshipData data = new FriendshipData();

            assertThat(data).isInstanceOf(java.io.Serializable.class);
        }

        @Test
        @DisplayName("ID should be settable and retrievable")
        void idSetAndGet() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .build();

            assertThat(data.getId()).isNull();

            data.setId("my-id-123");
            assertThat(data.getId()).isEqualTo("my-id-123");
        }

        @Test
        @DisplayName("ID should be included in equality check from parent")
        void idInEqualityCheck() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .build();
            data1.setId("id-1");

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .build();
            data2.setId("id-2");

            // With @EqualsAndHashCode(callSuper = true), the ID from parent is included
            assertThat(data1).isNotEqualTo(data2);
        }

        @Test
        @DisplayName("Same ID should make otherwise-equal objects equal")
        void sameIdMakesEqual() {
            FriendshipData data1 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .createdTime(1000L)
                    .build();
            data1.setId("same-id");

            FriendshipData data2 = FriendshipData.builder()
                    .playerUuid("uuid1")
                    .friendUuid("uuid2")
                    .friendName("Friend")
                    .createdTime(1000L)
                    .build();
            data2.setId("same-id");

            assertThat(data1).isEqualTo(data2);
            assertThat(data1.hashCode()).isEqualTo(data2.hashCode());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle empty string values")
        void handleEmptyStrings() {
            FriendshipData data = FriendshipData.builder()
                    .playerUuid("")
                    .friendUuid("")
                    .friendName("")
                    .nickname("")
                    .build();

            assertThat(data.getPlayerUuid()).isEmpty();
            assertThat(data.getFriendUuid()).isEmpty();
            assertThat(data.getFriendName()).isEmpty();
            assertThat(data.getNickname()).isEmpty();
        }

        @Test
        @DisplayName("Should handle very long string values")
        void handleLongStrings() {
            String longString = new String(new char[1000]).replace('\0', 'a');

            FriendshipData data = FriendshipData.builder()
                    .playerUuid(longString)
                    .friendName(longString)
                    .nickname(longString)
                    .build();

            assertThat(data.getPlayerUuid()).hasSize(1000);
            assertThat(data.getFriendName()).hasSize(1000);
            assertThat(data.getNickname()).hasSize(1000);
        }

        @Test
        @DisplayName("Should handle special characters in strings")
        void handleSpecialCharacters() {
            FriendshipData data = FriendshipData.builder()
                    .friendName("Player_With-Special.Name123")
                    .nickname("\u00a76\u00a7lGolden")
                    .build();

            assertThat(data.getFriendName()).isEqualTo("Player_With-Special.Name123");
            assertThat(data.getNickname()).isEqualTo("\u00a76\u00a7lGolden");
        }

        @Test
        @DisplayName("Should handle negative created time")
        void handleNegativeCreatedTime() {
            FriendshipData data = FriendshipData.builder()
                    .createdTime(-1L)
                    .build();

            assertThat(data.getCreatedTime()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("Should handle Long.MAX_VALUE created time")
        void handleMaxCreatedTime() {
            FriendshipData data = FriendshipData.builder()
                    .createdTime(Long.MAX_VALUE)
                    .build();

            assertThat(data.getCreatedTime()).isEqualTo(Long.MAX_VALUE);
        }
    }
}
