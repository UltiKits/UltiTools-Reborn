package com.ultikits.ultitools.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.PlayerCache;
import com.ultikits.ultitools.annotations.PlayerCacheSaver;
import com.ultikits.ultitools.exceptions.PluginModuleException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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

    static class SetCacheService {
        @PlayerCache
        final Set<UUID> notifiedPlayers = new HashSet<>();

        final Set<UUID> notAnnotatedSet = new HashSet<>();
    }

    static class ValueMapService {
        @PlayerCache
        final Map<String, UUID> serverLocks = new HashMap<>();

        final Map<String, UUID> notAnnotatedValueMap = new HashMap<>();
    }

    static class NestedKeyMapService {
        @PlayerCache
        final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    }

    static class UnsupportedShapeService {
        @PlayerCache
        final Map<String, String> badShape = new HashMap<>();
    }

    static class ValidatorLikeService {
        @PlayerCache
        final Map<UUID, String> state = new HashMap<>();
    }

    static class CountingSavingService implements PlayerCacheSaver {
        @PlayerCache(saveBeforeRemove = true)
        final Map<UUID, String> state = new HashMap<>();
        int saveCount = 0;

        @Override
        public void savePlayerData(UUID playerId) {
            saveCount++;
        }
    }

    /**
     * Stubs {@link UltiTools#getInstance()} to return a mock whose
     * {@code getPluginManager().getPlayerCacheManager()} chain resolves to the given manager.
     * Mirrors the workaround {@code PluginManagerRegisterInstanceOrderingTest} and
     * {@code CooldownValidatorTest} both use for the same "UltiTools is a final JavaPlugin"
     * problem.
     */
    private static MockedStatic<UltiTools> stubLiveManager(PlayerCacheManager liveManager) {
        UltiTools mockUltiTools = mock(UltiTools.class);
        PluginManager mockPluginManager = mock(PluginManager.class);
        when(mockPluginManager.getPlayerCacheManager()).thenReturn(liveManager);
        when(mockUltiTools.getPluginManager()).thenReturn(mockPluginManager);

        MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class);
        ultiToolsStatic.when(UltiTools::getInstance).thenReturn(mockUltiTools);
        return ultiToolsStatic;
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

    @Nested
    @DisplayName("Field shape support (D-03)")
    class FieldShapeSupport {

        @Test
        @DisplayName("Sweeps a @PlayerCache Set<UUID> field on quit")
        void sweepsSetOfUuid() {
            SetCacheService service = new SetCacheService();
            UUID playerUuid = UUID.randomUUID();
            UUID otherUuid = UUID.randomUUID();
            service.notifiedPlayers.add(playerUuid);
            service.notifiedPlayers.add(otherUuid);
            service.notAnnotatedSet.add(playerUuid);

            manager.registerBean(service);
            manager.onPlayerQuit(playerUuid);

            assertThat(service.notifiedPlayers).doesNotContain(playerUuid).contains(otherUuid);
            assertThat(service.notAnnotatedSet).contains(playerUuid);
        }

        @Test
        @DisplayName("Sweeps a @PlayerCache value-side Map<String, UUID> field on quit, leaving other players' entries")
        void sweepsValueSideUuidMap() {
            ValueMapService service = new ValueMapService();
            UUID playerUuid = UUID.randomUUID();
            UUID otherUuid = UUID.randomUUID();
            service.serverLocks.put("cmd.teleport", playerUuid);
            service.serverLocks.put("cmd.home", otherUuid);
            service.notAnnotatedValueMap.put("cmd.teleport", playerUuid);

            manager.registerBean(service);
            manager.onPlayerQuit(playerUuid);

            assertThat(service.serverLocks).doesNotContainValue(playerUuid);
            assertThat(service.serverLocks).containsValue(otherUuid);
            assertThat(service.notAnnotatedValueMap).containsValue(playerUuid);
        }

        @Test
        @DisplayName("Existing key-side Map<UUID, ?> sweep still removes the quitting player's entry (no regression)")
        void keepsKeySideMapBehavior() {
            TestService service = new TestService();
            UUID playerUuid = UUID.randomUUID();
            service.nameCache.put(playerUuid, "Alice");

            manager.registerBean(service);
            manager.onPlayerQuit(playerUuid);

            assertThat(service.nameCache).doesNotContainKey(playerUuid);
        }

        @Test
        @DisplayName("Sweeps a nested Map<UUID, Map<String, Long>> field's outer entry wholesale")
        void sweepsNestedKeySideMap() {
            NestedKeyMapService service = new NestedKeyMapService();
            UUID playerUuid = UUID.randomUUID();
            Map<String, Long> inner = new HashMap<>();
            inner.put("cmd.home", System.currentTimeMillis() + 60_000L);
            service.cooldowns.put(playerUuid, inner);

            manager.registerBean(service);
            manager.onPlayerQuit(playerUuid);

            assertThat(service.cooldowns).doesNotContainKey(playerUuid);
        }

        @Test
        @DisplayName("Refuses registration of an unsupported @PlayerCache field shape, naming class/field/supported shapes")
        void refusesUnsupportedFieldShape() {
            UnsupportedShapeService service = new UnsupportedShapeService();

            assertThatThrownBy(() -> manager.registerBean(service))
                    .isInstanceOf(PluginModuleException.class)
                    .hasMessageContaining(UnsupportedShapeService.class.getName())
                    .hasMessageContaining("badShape")
                    .hasMessageContaining("Map<UUID")
                    .hasMessageContaining("Set<UUID");

            assertThat(manager.getTrackedBeanCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Leaves an unannotated field untouched on quit regardless of its shape")
        void leavesUnannotatedFieldsUntouched() {
            SetCacheService setService = new SetCacheService();
            ValueMapService valueMapService = new ValueMapService();
            UUID playerUuid = UUID.randomUUID();
            setService.notAnnotatedSet.add(playerUuid);
            valueMapService.notAnnotatedValueMap.put("cmd.teleport", playerUuid);

            manager.registerBean(setService);
            manager.registerBean(valueMapService);
            manager.onPlayerQuit(playerUuid);

            assertThat(setService.notAnnotatedSet).contains(playerUuid);
            assertThat(valueMapService.notAnnotatedValueMap).containsValue(playerUuid);
        }
    }

    @Nested
    @DisplayName("Non-bean instance registration (D-03)")
    class NonBeanRegistration {

        @Test
        @DisplayName("tryRegister registers a new-ed, non-bean object; it is swept on quit")
        void tryRegisterRegistersAndSweeps() {
            ValidatorLikeService service = new ValidatorLikeService();
            UUID playerUuid = UUID.randomUUID();
            service.state.put(playerUuid, "value");

            try (MockedStatic<UltiTools> ignored = stubLiveManager(manager)) {
                PlayerCacheManager.tryRegister(service);
            }

            assertThat(manager.getTrackedBeanCount()).isEqualTo(1);
            manager.onPlayerQuit(playerUuid);
            assertThat(service.state).doesNotContainKey(playerUuid);
        }

        @Test
        @DisplayName("tryRegister does not throw when UltiTools.getInstance() is null; a bare new MyCommand() must not fail")
        void tryRegisterToleratesNullCoreInstance() {
            ValidatorLikeService service = new ValidatorLikeService();

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(null);

                assertThatCode(() -> PlayerCacheManager.tryRegister(service))
                        .doesNotThrowAnyException();
            }

            // Nothing reachable from this test was touched -- the outer per-test manager was
            // never given to the static resolver, so it must still show zero tracked beans.
            assertThat(manager.getTrackedBeanCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("A registration attempt made while the core plugin was unavailable succeeds on a later attempt")
        void tryRegisterSucceedsOnLaterAttemptOnceAvailable() {
            ValidatorLikeService service = new ValidatorLikeService();

            try (MockedStatic<UltiTools> ultiToolsStatic = mockStatic(UltiTools.class)) {
                ultiToolsStatic.when(UltiTools::getInstance).thenReturn(null);
                PlayerCacheManager.tryRegister(service);
            }
            assertThat(manager.getTrackedBeanCount()).isEqualTo(0);

            try (MockedStatic<UltiTools> ignored = stubLiveManager(manager)) {
                PlayerCacheManager.tryRegister(service);
            }
            assertThat(manager.getTrackedBeanCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Registering the same instance twice yields one tracked entry and one sweep per quit")
        void tryRegisterIsIdempotentPerInstance() {
            CountingSavingService service = new CountingSavingService();
            UUID playerUuid = UUID.randomUUID();
            service.state.put(playerUuid, "value");

            try (MockedStatic<UltiTools> ignored = stubLiveManager(manager)) {
                PlayerCacheManager.tryRegister(service);
                PlayerCacheManager.tryRegister(service);
            }

            assertThat(manager.getTrackedBeanCount()).isEqualTo(1);
            manager.onPlayerQuit(playerUuid);
            assertThat(service.saveCount)
                    .withFailMessage("savePlayerData must fire exactly once per quit, not once per duplicate registration")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("tryUnregister stops sweeping and releases the reference")
        void tryUnregisterReleasesReference() {
            ValidatorLikeService service = new ValidatorLikeService();
            UUID playerUuid = UUID.randomUUID();
            service.state.put(playerUuid, "value");
            int baseline = manager.getTrackedBeanCount();

            try (MockedStatic<UltiTools> ignored = stubLiveManager(manager)) {
                PlayerCacheManager.tryRegister(service);
                assertThat(manager.getTrackedBeanCount()).isEqualTo(baseline + 1);

                PlayerCacheManager.tryUnregister(service);
            }

            assertThat(manager.getTrackedBeanCount()).isEqualTo(baseline);
            manager.onPlayerQuit(playerUuid);
            assertThat(service.state)
                    .withFailMessage("an unregistered instance must not be swept on a later quit")
                    .containsKey(playerUuid);
        }

        @Test
        @DisplayName("Two distinct instances of the same class are tracked and unregistered independently")
        void distinctInstancesAreTrackedIndependently() {
            ValidatorLikeService first = new ValidatorLikeService();
            ValidatorLikeService second = new ValidatorLikeService();
            UUID playerUuid = UUID.randomUUID();
            first.state.put(playerUuid, "first");
            second.state.put(playerUuid, "second");

            try (MockedStatic<UltiTools> ignored = stubLiveManager(manager)) {
                PlayerCacheManager.tryRegister(first);
                PlayerCacheManager.tryRegister(second);
                assertThat(manager.getTrackedBeanCount()).isEqualTo(2);

                PlayerCacheManager.tryUnregister(first);
            }

            assertThat(manager.getTrackedBeanCount()).isEqualTo(1);
            manager.onPlayerQuit(playerUuid);
            assertThat(first.state)
                    .withFailMessage("unregistering one instance must not affect the other")
                    .containsKey(playerUuid);
            assertThat(second.state).doesNotContainKey(playerUuid);
        }
    }
}
