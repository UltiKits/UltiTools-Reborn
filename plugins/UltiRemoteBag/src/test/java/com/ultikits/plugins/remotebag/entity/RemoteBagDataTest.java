package com.ultikits.plugins.remotebag.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RemoteBagData Tests")
class RemoteBagDataTest {

    @Nested
    @DisplayName("Factory Method")
    class FactoryMethod {

        @Test
        @DisplayName("Should create with all fields from factory")
        void createFactory() {
            UUID uuid = UUID.randomUUID();
            String contents = "test-contents";

            RemoteBagData data = RemoteBagData.create(uuid, 1, contents);

            assertThat(data.getPlayerUuid()).isEqualTo(uuid.toString());
            assertThat(data.getPageNumber()).isEqualTo(1);
            assertThat(data.getContents()).isEqualTo(contents);
            assertThat(data.getLastUpdated()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should set timestamp close to current time")
        void setsTimestamp() {
            long before = System.currentTimeMillis();
            RemoteBagData data = RemoteBagData.create(UUID.randomUUID(), 1, "test");
            long after = System.currentTimeMillis();

            assertThat(data.getLastUpdated()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all fields")
        void buildAllFields() {
            RemoteBagData data = RemoteBagData.builder()
                    .playerUuid("uuid-123")
                    .pageNumber(5)
                    .contents("yaml-contents")
                    .lastUpdated(12345L)
                    .build();

            assertThat(data.getPlayerUuid()).isEqualTo("uuid-123");
            assertThat(data.getPageNumber()).isEqualTo(5);
            assertThat(data.getContents()).isEqualTo("yaml-contents");
            assertThat(data.getLastUpdated()).isEqualTo(12345L);
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update player UUID")
        void setPlayerUuid() {
            RemoteBagData data = new RemoteBagData();
            data.setPlayerUuid("new-uuid");
            assertThat(data.getPlayerUuid()).isEqualTo("new-uuid");
        }

        @Test
        @DisplayName("Should update page number")
        void setPageNumber() {
            RemoteBagData data = new RemoteBagData();
            data.setPageNumber(10);
            assertThat(data.getPageNumber()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should update contents")
        void setContents() {
            RemoteBagData data = new RemoteBagData();
            data.setContents("new-contents");
            assertThat(data.getContents()).isEqualTo("new-contents");
        }

        @Test
        @DisplayName("Should update last updated")
        void setLastUpdated() {
            RemoteBagData data = new RemoteBagData();
            data.setLastUpdated(99999L);
            assertThat(data.getLastUpdated()).isEqualTo(99999L);
        }
    }

    @Nested
    @DisplayName("No-arg Constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("Should create with default values")
        void defaults() {
            RemoteBagData data = new RemoteBagData();
            assertThat(data.getPlayerUuid()).isNull();
            assertThat(data.getPageNumber()).isZero();
            assertThat(data.getContents()).isNull();
            assertThat(data.getLastUpdated()).isZero();
        }
    }

    @Nested
    @DisplayName("All-args Constructor")
    class AllArgsConstructor {

        @Test
        @DisplayName("Should create with all fields")
        void allFields() {
            RemoteBagData data = new RemoteBagData("uuid-all", 3, "content-all", 77777L);

            assertThat(data.getPlayerUuid()).isEqualTo("uuid-all");
            assertThat(data.getPageNumber()).isEqualTo(3);
            assertThat(data.getContents()).isEqualTo("content-all");
            assertThat(data.getLastUpdated()).isEqualTo(77777L);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("Should be equal when same values")
        void equalsSameValues() {
            RemoteBagData a = RemoteBagData.builder()
                    .playerUuid("uuid-1")
                    .pageNumber(1)
                    .contents("content")
                    .lastUpdated(1000L)
                    .build();
            RemoteBagData b = RemoteBagData.builder()
                    .playerUuid("uuid-1")
                    .pageNumber(1)
                    .contents("content")
                    .lastUpdated(1000L)
                    .build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when different UUID")
        void notEqualDifferentUuid() {
            RemoteBagData a = RemoteBagData.builder()
                    .playerUuid("uuid-1")
                    .pageNumber(1)
                    .build();
            RemoteBagData b = RemoteBagData.builder()
                    .playerUuid("uuid-2")
                    .pageNumber(1)
                    .build();

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Should not be equal when different page")
        void notEqualDifferentPage() {
            RemoteBagData a = RemoteBagData.builder()
                    .playerUuid("uuid-1")
                    .pageNumber(1)
                    .build();
            RemoteBagData b = RemoteBagData.builder()
                    .playerUuid("uuid-1")
                    .pageNumber(2)
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
            RemoteBagData data = RemoteBagData.builder()
                    .playerUuid("uuid-123")
                    .pageNumber(5)
                    .build();

            String str = data.toString();
            assertThat(str).contains("uuid-123");
            assertThat(str).contains("5");
        }
    }
}
