package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.HashMap;
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

    static class BaseWithCache {
        @PlayerCache
        protected final Map<UUID, String> inheritedCache = new HashMap<>();
    }

    static class ChildBean extends BaseWithCache {
        @PlayerCache
        protected final Map<UUID, String> ownCache = new HashMap<>();
    }

    static class ShadowBase {
        @PlayerCache
        private final Map<UUID, String> cache = new HashMap<>();
    }

    static class ShadowChild extends ShadowBase {
        @PlayerCache
        protected final Map<UUID, String> cache = new HashMap<>();
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

    @Nested
    @DisplayName("Inherited field scanning")
    class InheritedFields {

        @Test
        @DisplayName("Should track @PlayerCache fields declared on a superclass")
        void shouldTrackInheritedField() {
            PlayerCacheManager testManager = new PlayerCacheManager();
            ChildBean bean = new ChildBean();
            UUID player = UUID.randomUUID();
            bean.inheritedCache.put(player, "inherited");
            bean.ownCache.put(player, "own");

            testManager.registerBean(bean);
            testManager.onPlayerQuit(player);

            assertThat(bean.ownCache).isEmpty();
            assertThat(bean.inheritedCache).isEmpty();
        }

        @Test
        @DisplayName("Should count a bean once even when fields come from several levels")
        void shouldCountBeanOnce() {
            PlayerCacheManager testManager = new PlayerCacheManager();
            testManager.registerBean(new ChildBean());
            assertThat(testManager.getTrackedBeanCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Field shadowing (same-name fields in hierarchy)")
    class FieldShadowing {

        @Test
        @DisplayName("Should clean both parent and child @PlayerCache fields when shadowed by same name")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void shouldCleanBothShadowedFields() throws NoSuchFieldException, IllegalAccessException {
            PlayerCacheManager testManager = new PlayerCacheManager();
            ShadowChild bean = new ShadowChild();
            UUID player = UUID.randomUUID();

            // Access parent's private field via reflection to populate both caches
            Field baseField = ShadowBase.class.getDeclaredField("cache");
            baseField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, String> parentCache = (Map<UUID, String>) baseField.get(bean);
            parentCache.put(player, "parent");

            // Access child's field to populate its cache
            Field childField = ShadowChild.class.getDeclaredField("cache");
            childField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, String> childCache = (Map<UUID, String>) childField.get(bean);
            childCache.put(player, "child");

            testManager.registerBean(bean);
            testManager.onPlayerQuit(player);

            // Both maps must be empty; if de-duplication by field name is added, parent cache
            // would not be cleaned
            assertThat(parentCache).withFailMessage("parent cache (from ShadowBase) must be cleaned").isEmpty();
            assertThat(childCache).withFailMessage("child cache (from ShadowChild) must be cleaned").isEmpty();
        }
    }
}
