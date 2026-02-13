package com.ultikits.plugins.essentials.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Lombok-generated equals(), hashCode(), toString(), and canEqual()
 * methods on all entity classes.
 * <p>
 * 测试所有实体类的 Lombok 生成方法。
 *
 * @author wisdomme
 * @version 1.0.0
 */
@DisplayName("Entity equals/hashCode/toString Tests")
class EntityEqualsHashCodeTest {

    // ---- BanData ----

    @Nested
    @DisplayName("BanData Lombok Methods")
    class BanDataLombokTests {

        @Test
        @DisplayName("Should equal itself")
        void shouldEqualItself() {
            UUID uuid = UUID.randomUUID();
            BanData ban = BanData.builder()
                    .uuid(uuid)
                    .playerUuid("p1")
                    .playerName("Steve")
                    .reason("test")
                    .bannedBy("admin")
                    .bannedByName("Admin")
                    .banTime(1000L)
                    .expireTime(-1)
                    .active(true)
                    .ipAddress("127.0.0.1")
                    .build();
            assertThat(ban).isEqualTo(ban);
            assertThat(ban.hashCode()).isEqualTo(ban.hashCode());
        }

        @Test
        @DisplayName("Should equal another with same fields")
        void shouldEqualSameFields() {
            UUID uuid = UUID.randomUUID();
            BanData ban1 = BanData.builder()
                    .uuid(uuid).playerUuid("p1").playerName("Steve")
                    .reason("test").bannedBy("a").bannedByName("A")
                    .banTime(1000L).expireTime(-1).active(true).ipAddress("1.2.3.4")
                    .build();
            BanData ban2 = BanData.builder()
                    .uuid(uuid).playerUuid("p1").playerName("Steve")
                    .reason("test").bannedBy("a").bannedByName("A")
                    .banTime(1000L).expireTime(-1).active(true).ipAddress("1.2.3.4")
                    .build();
            assertThat(ban1).isEqualTo(ban2);
            assertThat(ban1.hashCode()).isEqualTo(ban2.hashCode());
        }

        @Test
        @DisplayName("Should not equal with different fields")
        void shouldNotEqualDifferentFields() {
            BanData ban1 = BanData.builder().playerName("Steve").build();
            BanData ban2 = BanData.builder().playerName("Alex").build();
            assertThat(ban1).isNotEqualTo(ban2);
        }

        @Test
        @DisplayName("Should not equal null")
        void shouldNotEqualNull() {
            BanData ban = BanData.builder().build();
            assertThat(ban).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not equal different type")
        void shouldNotEqualDifferentType() {
            BanData ban = BanData.builder().build();
            assertThat(ban).isNotEqualTo("string");
        }

        @Test
        @DisplayName("Should produce non-null toString")
        void shouldProduceToString() {
            BanData ban = BanData.builder()
                    .playerName("Steve").reason("test").active(true)
                    .build();
            String str = ban.toString();
            assertThat(str).isNotNull();
            assertThat(str).contains("Steve");
            assertThat(str).contains("test");
        }

        @Test
        @DisplayName("Should support canEqual")
        void shouldSupportCanEqual() {
            BanData ban1 = BanData.builder().build();
            BanData ban2 = BanData.builder().build();
            assertThat(ban1.canEqual(ban2)).isTrue();
            assertThat(ban1.canEqual("not a BanData")).isFalse();
        }
    }

    // ---- ChestLockData ----

    @Nested
    @DisplayName("ChestLockData Lombok Methods")
    class ChestLockDataLombokTests {

        @Test
        @DisplayName("Should equal with same fields")
        void shouldEqualSameFields() {
            UUID uuid = UUID.randomUUID();
            ChestLockData d1 = ChestLockData.builder()
                    .uuid(uuid).ownerUuid("o1").ownerName("Steve")
                    .world("world").x(10).y(64).z(-20)
                    .build();
            ChestLockData d2 = ChestLockData.builder()
                    .uuid(uuid).ownerUuid("o1").ownerName("Steve")
                    .world("world").x(10).y(64).z(-20)
                    .build();
            assertThat(d1).isEqualTo(d2);
            assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
        }

        @Test
        @DisplayName("Should not equal different fields")
        void shouldNotEqualDifferentFields() {
            ChestLockData d1 = ChestLockData.builder().x(10).build();
            ChestLockData d2 = ChestLockData.builder().x(20).build();
            assertThat(d1).isNotEqualTo(d2);
        }

        @Test
        @DisplayName("Should produce toString with fields")
        void shouldProduceToString() {
            ChestLockData d = ChestLockData.builder()
                    .ownerName("Steve").world("world").x(10).y(64).z(-20)
                    .build();
            assertThat(d.toString()).contains("Steve");
            assertThat(d.toString()).contains("world");
        }

        @Test
        @DisplayName("Should support canEqual")
        void shouldSupportCanEqual() {
            ChestLockData d1 = ChestLockData.builder().build();
            assertThat(d1.canEqual(ChestLockData.builder().build())).isTrue();
            assertThat(d1.canEqual("string")).isFalse();
        }
    }

    // ---- HomeData ----

    @Nested
    @DisplayName("HomeData Lombok Methods")
    class HomeDataLombokTests {

        @Test
        @DisplayName("Should equal with same fields")
        void shouldEqualSameFields() {
            UUID uuid = UUID.randomUUID();
            HomeData d1 = HomeData.builder()
                    .uuid(uuid).playerUuid("p1").name("home")
                    .world("world").x(10.0).y(64.0).z(-20.0)
                    .yaw(90.0f).pitch(0.0f)
                    .build();
            HomeData d2 = HomeData.builder()
                    .uuid(uuid).playerUuid("p1").name("home")
                    .world("world").x(10.0).y(64.0).z(-20.0)
                    .yaw(90.0f).pitch(0.0f)
                    .build();
            assertThat(d1).isEqualTo(d2);
            assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
        }

        @Test
        @DisplayName("Should not equal different home name")
        void shouldNotEqualDifferentHomeName() {
            HomeData d1 = HomeData.builder().name("home1").build();
            HomeData d2 = HomeData.builder().name("home2").build();
            assertThat(d1).isNotEqualTo(d2);
        }

        @Test
        @DisplayName("Should produce toString")
        void shouldProduceToString() {
            HomeData d = HomeData.builder().name("myHome").world("world").build();
            assertThat(d.toString()).contains("myHome");
        }

        @Test
        @DisplayName("Should support canEqual")
        void shouldSupportCanEqual() {
            HomeData d = HomeData.builder().build();
            assertThat(d.canEqual(HomeData.builder().build())).isTrue();
            assertThat(d.canEqual(new Object())).isFalse();
        }
    }

    // ---- WarpData ----

    @Nested
    @DisplayName("WarpData Lombok Methods")
    class WarpDataLombokTests {

        @Test
        @DisplayName("Should equal with same fields")
        void shouldEqualSameFields() {
            UUID uuid = UUID.randomUUID();
            WarpData d1 = WarpData.builder()
                    .uuid(uuid).name("spawn").permission("warp.spawn")
                    .world("world").x(0.0).y(64.0).z(0.0)
                    .yaw(0.0f).pitch(0.0f)
                    .build();
            WarpData d2 = WarpData.builder()
                    .uuid(uuid).name("spawn").permission("warp.spawn")
                    .world("world").x(0.0).y(64.0).z(0.0)
                    .yaw(0.0f).pitch(0.0f)
                    .build();
            assertThat(d1).isEqualTo(d2);
            assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
        }

        @Test
        @DisplayName("Should not equal different warp name")
        void shouldNotEqualDifferentWarpName() {
            WarpData d1 = WarpData.builder().name("spawn").build();
            WarpData d2 = WarpData.builder().name("shop").build();
            assertThat(d1).isNotEqualTo(d2);
        }

        @Test
        @DisplayName("Should produce toString")
        void shouldProduceToString() {
            WarpData d = WarpData.builder().name("spawn").build();
            assertThat(d.toString()).contains("spawn");
        }

        @Test
        @DisplayName("Should support canEqual")
        void shouldSupportCanEqual() {
            WarpData d = WarpData.builder().build();
            assertThat(d.canEqual(WarpData.builder().build())).isTrue();
            assertThat(d.canEqual("text")).isFalse();
        }
    }

    // ---- Cross-type equality ----

    @Nested
    @DisplayName("Cross-type Equality")
    class CrossTypeTests {

        @Test
        @DisplayName("Different entity types should not be equal")
        void differentTypesShouldNotBeEqual() {
            BanData ban = BanData.builder().build();
            ChestLockData lock = ChestLockData.builder().build();

            assertThat(ban).isNotEqualTo(lock);
        }

        @Test
        @DisplayName("HomeData and WarpData should not be equal even with same coordinates")
        void homeAndWarpShouldNotBeEqual() {
            HomeData home = HomeData.builder()
                    .world("world").x(10.0).y(64.0).z(-20.0)
                    .build();
            WarpData warp = WarpData.builder()
                    .world("world").x(10.0).y(64.0).z(-20.0)
                    .build();

            assertThat(home).isNotEqualTo(warp);
        }
    }
}
