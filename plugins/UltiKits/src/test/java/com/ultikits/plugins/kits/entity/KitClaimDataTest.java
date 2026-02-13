package com.ultikits.plugins.kits.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KitClaimData")
class KitClaimDataTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds with all fields set")
        void buildsWithAllFields() {
            UUID id = UUID.randomUUID();
            String playerUuid = UUID.randomUUID().toString();

            KitClaimData data = KitClaimData.builder()
                    .uuid(id)
                    .playerUuid(playerUuid)
                    .kitName("starter")
                    .lastClaim(1000L)
                    .claimCount(3)
                    .build();

            assertThat(data.getUuid()).isEqualTo(id);
            assertThat(data.getPlayerUuid()).isEqualTo(playerUuid);
            assertThat(data.getKitName()).isEqualTo("starter");
            assertThat(data.getLastClaim()).isEqualTo(1000L);
            assertThat(data.getClaimCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("builder defaults are null/zero")
        void builderDefaults() {
            KitClaimData data = KitClaimData.builder().build();

            assertThat(data.getUuid()).isNull();
            assertThat(data.getPlayerUuid()).isNull();
            assertThat(data.getKitName()).isNull();
            assertThat(data.getLastClaim()).isEqualTo(0L);
            assertThat(data.getClaimCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Get/Set ID")
    class GetSetId {

        @Test
        @DisplayName("getId returns uuid field")
        void getIdReturnsUuid() {
            UUID id = UUID.randomUUID();
            KitClaimData data = KitClaimData.builder().uuid(id).build();

            assertThat(data.getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("setId updates uuid field")
        void setIdUpdatesUuid() {
            KitClaimData data = new KitClaimData();
            UUID id = UUID.randomUUID();

            data.setId(id);

            assertThat(data.getUuid()).isEqualTo(id);
            assertThat(data.getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("setId with null sets uuid to null")
        void setIdNull() {
            UUID id = UUID.randomUUID();
            KitClaimData data = KitClaimData.builder().uuid(id).build();

            data.setId(null);

            assertThat(data.getUuid()).isNull();
            assertThat(data.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("IsNew")
    class IsNewTests {

        @Test
        @DisplayName("isNew returns true when uuid is null")
        void isNewWhenNullUuid() {
            KitClaimData data = new KitClaimData();
            assertThat(data.isNew()).isTrue();
        }

        @Test
        @DisplayName("isNew returns false when uuid is set")
        void isNotNewWhenUuidSet() {
            KitClaimData data = KitClaimData.builder()
                    .uuid(UUID.randomUUID())
                    .build();
            assertThat(data.isNew()).isFalse();
        }
    }

    @Nested
    @DisplayName("No-arg Constructor Defaults")
    class Defaults {

        @Test
        @DisplayName("no-arg constructor sets fields to defaults")
        void noArgDefaults() {
            KitClaimData data = new KitClaimData();

            assertThat(data.getUuid()).isNull();
            assertThat(data.getPlayerUuid()).isNull();
            assertThat(data.getKitName()).isNull();
            assertThat(data.getLastClaim()).isEqualTo(0L);
            assertThat(data.getClaimCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("All-args Constructor")
    class AllArgsConstructor {

        @Test
        @DisplayName("all-args constructor sets all fields")
        void allArgsConstructor() {
            UUID id = UUID.randomUUID();
            String playerUuid = "player-123";

            KitClaimData data = new KitClaimData(id, playerUuid, "vip", 5000L, 10);

            assertThat(data.getUuid()).isEqualTo(id);
            assertThat(data.getPlayerUuid()).isEqualTo(playerUuid);
            assertThat(data.getKitName()).isEqualTo("vip");
            assertThat(data.getLastClaim()).isEqualTo(5000L);
            assertThat(data.getClaimCount()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Table Annotation")
    class TableAnnotation {

        @Test
        @DisplayName("class has @Table annotation with correct table name")
        void hasTableAnnotation() {
            com.ultikits.ultitools.annotations.Table table =
                    KitClaimData.class.getAnnotation(com.ultikits.ultitools.annotations.Table.class);

            assertThat(table).isNotNull();
            assertThat(table.value()).isEqualTo("kits_claims");
        }
    }

    @Nested
    @DisplayName("Column Annotations")
    class ColumnAnnotations {

        @Test
        @DisplayName("uuid field has @Column('uuid')")
        void uuidColumn() throws NoSuchFieldException {
            com.ultikits.ultitools.annotations.Column column =
                    KitClaimData.class.getDeclaredField("uuid")
                            .getAnnotation(com.ultikits.ultitools.annotations.Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("uuid");
        }

        @Test
        @DisplayName("playerUuid field has @Column('player_uuid')")
        void playerUuidColumn() throws NoSuchFieldException {
            com.ultikits.ultitools.annotations.Column column =
                    KitClaimData.class.getDeclaredField("playerUuid")
                            .getAnnotation(com.ultikits.ultitools.annotations.Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("player_uuid");
        }

        @Test
        @DisplayName("kitName field has @Column('kit_name')")
        void kitNameColumn() throws NoSuchFieldException {
            com.ultikits.ultitools.annotations.Column column =
                    KitClaimData.class.getDeclaredField("kitName")
                            .getAnnotation(com.ultikits.ultitools.annotations.Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("kit_name");
        }

        @Test
        @DisplayName("lastClaim field has @Column('last_claim')")
        void lastClaimColumn() throws NoSuchFieldException {
            com.ultikits.ultitools.annotations.Column column =
                    KitClaimData.class.getDeclaredField("lastClaim")
                            .getAnnotation(com.ultikits.ultitools.annotations.Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("last_claim");
        }

        @Test
        @DisplayName("claimCount field has @Column('claim_count')")
        void claimCountColumn() throws NoSuchFieldException {
            com.ultikits.ultitools.annotations.Column column =
                    KitClaimData.class.getDeclaredField("claimCount")
                            .getAnnotation(com.ultikits.ultitools.annotations.Column.class);
            assertThat(column).isNotNull();
            assertThat(column.value()).isEqualTo("claim_count");
        }
    }

    @Nested
    @DisplayName("Setters")
    class SetterTests {

        @Test
        @DisplayName("setPlayerUuid updates playerUuid")
        void setPlayerUuid() {
            KitClaimData data = new KitClaimData();
            data.setPlayerUuid("abc-123");
            assertThat(data.getPlayerUuid()).isEqualTo("abc-123");
        }

        @Test
        @DisplayName("setKitName updates kitName")
        void setKitName() {
            KitClaimData data = new KitClaimData();
            data.setKitName("warrior");
            assertThat(data.getKitName()).isEqualTo("warrior");
        }

        @Test
        @DisplayName("setLastClaim updates lastClaim")
        void setLastClaim() {
            KitClaimData data = new KitClaimData();
            data.setLastClaim(999L);
            assertThat(data.getLastClaim()).isEqualTo(999L);
        }

        @Test
        @DisplayName("setClaimCount updates claimCount")
        void setClaimCount() {
            KitClaimData data = new KitClaimData();
            data.setClaimCount(42);
            assertThat(data.getClaimCount()).isEqualTo(42);
        }
    }
}
