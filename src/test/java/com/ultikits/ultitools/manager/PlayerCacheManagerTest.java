package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;

@DisplayName("PlayerCacheManager Tests")
class PlayerCacheManagerTest {

    private PlayerCacheManager manager;

    @BeforeEach
    void setUp() {
        manager = new PlayerCacheManager();
    }

    static class TestService {
        @PlayerCache
        final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

        final Map<UUID, Long> notAnnotated = new ConcurrentHashMap<>();
    }

    static class SavingService implements PlayerCacheSaver {
        @PlayerCache(saveBeforeRemove = true)
        final Map<UUID, String> dataCache = new ConcurrentHashMap<>();
        boolean saveCalled = false;
        UUID savedPlayer = null;

        @Override
        public void savePlayerData(UUID playerId) {
            saveCalled = true;
            savedPlayer = playerId;
        }
    }

    @Nested
    @DisplayName("Bean Registration")
    class Registration {
        @Test
        @DisplayName("Registers bean with @PlayerCache field")
        void registersBeanWithAnnotation() {
            TestService service = new TestService();
            manager.registerBean(service);
            assertThat(manager.getTrackedBeanCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Ignores bean without @PlayerCache fields")
        void ignoresBeanWithoutAnnotation() {
            Object plainBean = new Object();
            manager.registerBean(plainBean);
            assertThat(manager.getTrackedBeanCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Player Quit Cleanup")
    class Cleanup {
        @Test
        @DisplayName("Removes player UUID from annotated map on quit")
        void removesFromAnnotatedMap() {
            TestService service = new TestService();
            UUID playerUuid = UUID.randomUUID();
            service.nameCache.put(playerUuid, "Alice");
            service.notAnnotated.put(playerUuid, 100L);

            manager.registerBean(service);
            manager.onPlayerQuit(playerUuid);

            assertThat(service.nameCache).doesNotContainKey(playerUuid);
            assertThat(service.notAnnotated).containsKey(playerUuid);
        }

        @Test
        @DisplayName("Calls savePlayerData before removing when saveBeforeRemove=true")
        void callsSaveBeforeRemove() {
            SavingService service = new SavingService();
            UUID playerUuid = UUID.randomUUID();
            service.dataCache.put(playerUuid, "data");

            manager.registerBean(service);
            manager.onPlayerQuit(playerUuid);

            assertThat(service.saveCalled).isTrue();
            assertThat(service.savedPlayer).isEqualTo(playerUuid);
            assertThat(service.dataCache).doesNotContainKey(playerUuid);
        }

        @Test
        @DisplayName("Does not call savePlayerData when saveBeforeRemove=false")
        void doesNotCallSaveForDefault() {
            TestService service = new TestService();
            UUID playerUuid = UUID.randomUUID();
            service.nameCache.put(playerUuid, "Bob");

            manager.registerBean(service);
            manager.onPlayerQuit(playerUuid);

            assertThat(service.nameCache).doesNotContainKey(playerUuid);
        }
    }

    @Nested
    @DisplayName("Unregistration")
    class Unregistration {
        @Test
        @DisplayName("Unregisters bean and stops tracking")
        void unregisterBean() {
            TestService service = new TestService();
            manager.registerBean(service);
            assertThat(manager.getTrackedBeanCount()).isEqualTo(1);

            manager.unregisterBean(service);
            assertThat(manager.getTrackedBeanCount()).isEqualTo(0);
        }
    }
}
