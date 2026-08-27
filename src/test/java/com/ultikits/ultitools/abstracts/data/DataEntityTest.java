package com.ultikits.ultitools.abstracts.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.command.AsyncCommand;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;

/**
 * Unit tests for BaseDataEntity and AuditableDataEntity.
 */
@DisplayName("Data Entity Tests")
public class DataEntityTest {

    @AfterEach
    void tearDown() {
        // Always clear current user after each test
        AuditableDataEntity.clearCurrentUser();
        CountingAuditableEntity.resetCounters();
    }

    @Nested
    @DisplayName("BaseDataEntity Tests")
    class BaseDataEntityTests {

        @Test
        @DisplayName("Should create entity with UUID ID")
        void shouldCreateWithUuidId() {
            TestEntity entity = new TestEntity();
            UUID id = UUID.randomUUID();
            entity.setId(id);
            
            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should create entity with Long ID")
        void shouldCreateWithLongId() {
            TestLongEntity entity = new TestLongEntity();
            entity.setId(123L);
            
            assertEquals(123L, entity.getId());
        }

        @Test
        @DisplayName("Should call onCreate hook")
        void shouldCallOnCreateHook() {
            TestEntity entity = new TestEntity();
            entity.onCreate();
            
            assertTrue(entity.isOnCreateCalled());
        }

        @Test
        @DisplayName("Should call onUpdate hook")
        void shouldCallOnUpdateHook() {
            TestEntity entity = new TestEntity();
            entity.onUpdate();
            
            assertTrue(entity.isOnUpdateCalled());
        }

        @Test
        @DisplayName("Should call onDelete hook")
        void shouldCallOnDeleteHook() {
            TestEntity entity = new TestEntity();
            entity.onDelete();
            
            assertTrue(entity.isOnDeleteCalled());
        }

        @Test
        @DisplayName("Should call onLoad hook")
        void shouldCallOnLoadHook() {
            TestEntity entity = new TestEntity();
            entity.onLoad();
            
            assertTrue(entity.isOnLoadCalled());
        }

        @Test
        @DisplayName("Should pass validation when valid")
        void shouldPassValidation() {
            TestEntity entity = new TestEntity();
            entity.setName("ValidName");
            
            assertTrue(entity.validate());
        }

        @Test
        @DisplayName("Should fail validation when invalid")
        void shouldFailValidation() {
            TestEntity entity = new TestEntity();
            entity.setName(null);
            
            assertFalse(entity.validate());
        }

        @Test
        @DisplayName("Should track if entity is new")
        void shouldTrackIsNew() {
            TestEntity entity = new TestEntity();
            assertTrue(entity.isNew());
            
            entity.setId(UUID.randomUUID());
            assertFalse(entity.isNew());
        }
        
        @Test
        @DisplayName("Should return empty validation errors list by default")
        void shouldReturnEmptyValidationErrorsByDefault() {
            SimpleEntity entity = new SimpleEntity();
            
            List<String> errors = entity.getValidationErrors();
            
            assertNotNull(errors);
            assertTrue(errors.isEmpty());
        }
        
        @Test
        @DisplayName("Should return custom validation errors when overridden")
        void shouldReturnCustomValidationErrors() {
            TestEntity entity = new TestEntity();
            entity.setName(null);
            
            List<String> errors = entity.getValidationErrors();
            
            assertNotNull(errors);
            assertFalse(errors.isEmpty());
            assertTrue(errors.contains("Name is required"));
        }
        
        @Test
        @DisplayName("Should validate returns true by default")
        void shouldValidateReturnsTrueByDefault() {
            SimpleEntity entity = new SimpleEntity();
            
            assertTrue(entity.validate());
        }
        
        @Test
        @DisplayName("Should copy entity without ID")
        void shouldCopyEntityWithoutId() {
            CloneableEntity entity = new CloneableEntity();
            entity.setId(UUID.randomUUID());
            entity.setName("TestName");
            entity.setValue(42);
            
            CloneableEntity copy = entity.copyWithoutId();
            
            assertNotNull(copy);
            assertNull(copy.getId());
            assertEquals("TestName", copy.getName());
            assertEquals(42, copy.getValue());
            assertNotSame(entity, copy);
        }
        
        @Test
        @DisplayName("Should throw RuntimeException when clone not supported")
        void shouldThrowWhenCloneNotSupported() {
            NonCloneableEntity entity = new NonCloneableEntity();
            entity.setId(UUID.randomUUID());
            
            assertThrows(RuntimeException.class, () -> entity.copyWithoutId());
        }
        
        @Test
        @DisplayName("Should use ID for equals and hashCode")
        void shouldUseIdForEqualsAndHashCode() {
            UUID id = UUID.randomUUID();
            TestEntity entity1 = new TestEntity();
            entity1.setId(id);
            entity1.setName("Name1");
            
            TestEntity entity2 = new TestEntity();
            entity2.setId(id);
            entity2.setName("Name2");  // Different name, same ID
            
            assertEquals(entity1, entity2);
            assertEquals(entity1.hashCode(), entity2.hashCode());
        }
        
        @Test
        @DisplayName("Should not be equal with different IDs")
        void shouldNotBeEqualWithDifferentIds() {
            TestEntity entity1 = new TestEntity();
            entity1.setId(UUID.randomUUID());
            
            TestEntity entity2 = new TestEntity();
            entity2.setId(UUID.randomUUID());
            
            assertFalse(entity1.equals(entity2));
        }
    }

    @Nested
    @DisplayName("AuditableDataEntity Tests")
    class AuditableDataEntityTests {

        @Test
        @DisplayName("Should set audit fields on create")
        void shouldSetAuditFieldsOnCreate() {
            TestAuditableEntity entity = new TestAuditableEntity();
            
            // Simulate setting current user
            UUID testUserId = UUID.randomUUID();
            AuditableDataEntity.setCurrentUser(testUserId);
            try {
                entity.onCreate();
                
                assertNotNull(entity.getCreatedAt());
                assertEquals(testUserId, entity.getCreatedBy());
            } finally {
                AuditableDataEntity.clearCurrentUser();
            }
        }

        @Test
        @DisplayName("Should set audit fields on update")
        void shouldSetAuditFieldsOnUpdate() {
            TestAuditableEntity entity = new TestAuditableEntity();
            
            UUID testUserId = UUID.randomUUID();
            AuditableDataEntity.setCurrentUser(testUserId);
            try {
                entity.onUpdate();
                
                assertNotNull(entity.getUpdatedAt());
                assertEquals(testUserId, entity.getUpdatedBy());
            } finally {
                AuditableDataEntity.clearCurrentUser();
            }
        }

        @Test
        @DisplayName("Should use null user when none set")
        void shouldUseNullUserWhenNoneSet() {
            TestAuditableEntity entity = new TestAuditableEntity();
            
            entity.onCreate();
            
            // When no user is set, createdBy should be null
            assertNull(entity.getCreatedBy());
        }
        
        @Test
        @DisplayName("Should update audit fields on update without user")
        void shouldUpdateAuditFieldsWithoutUser() {
            TestAuditableEntity entity = new TestAuditableEntity();
            
            entity.onUpdate();
            
            assertNotNull(entity.getUpdatedAt());
            assertNull(entity.getUpdatedBy());
        }
        
        @Test
        @DisplayName("Should return null age when not persisted")
        void shouldReturnNullAgeWhenNotPersisted() {
            TestAuditableEntity entity = new TestAuditableEntity();
            
            assertNull(entity.getAge());
        }
        
        @Test
        @DisplayName("Should return age when created")
        void shouldReturnAgeWhenCreated() throws InterruptedException {
            TestAuditableEntity entity = new TestAuditableEntity();
            entity.onCreate();
            
            // Wait a tiny bit to ensure duration is positive
            Thread.sleep(10);
            
            java.time.Duration age = entity.getAge();
            
            assertNotNull(age);
            assertTrue(age.toMillis() >= 10);
        }
        
        @Test
        @DisplayName("Should return null time since update when not updated")
        void shouldReturnNullTimeSinceUpdateWhenNotUpdated() {
            TestAuditableEntity entity = new TestAuditableEntity();
            
            assertNull(entity.getTimeSinceUpdate());
        }
        
        @Test
        @DisplayName("Should return time since update when updated")
        void shouldReturnTimeSinceUpdateWhenUpdated() throws InterruptedException {
            TestAuditableEntity entity = new TestAuditableEntity();
            entity.onUpdate();
            
            Thread.sleep(10);
            
            java.time.Duration timeSinceUpdate = entity.getTimeSinceUpdate();
            
            assertNotNull(timeSinceUpdate);
            assertTrue(timeSinceUpdate.toMillis() >= 10);
        }
        
        @Test
        @DisplayName("Should return false for wasModified when not persisted")
        void shouldReturnFalseWasModifiedWhenNotPersisted() {
            TestAuditableEntity entity = new TestAuditableEntity();
            
            assertFalse(entity.wasModified());
        }
        
        @Test
        @DisplayName("Should return false for wasModified when only created")
        void shouldReturnFalseWasModifiedWhenOnlyCreated() {
            TestAuditableEntity entity = new TestAuditableEntity();
            entity.onCreate();
            
            // Right after creation, updatedAt == createdAt
            assertFalse(entity.wasModified());
        }
        
        @Test
        @DisplayName("Should return true for wasModified after update")
        void shouldReturnTrueWasModifiedAfterUpdate() throws InterruptedException {
            TestAuditableEntity entity = new TestAuditableEntity();
            entity.onCreate();
            
            // Wait to ensure different timestamps
            Thread.sleep(10);
            
            entity.onUpdate();
            
            assertTrue(entity.wasModified());
        }
        
        @Test
        @DisplayName("Should return false for wasModified when createdAt is null")
        void shouldReturnFalseWasModifiedWhenCreatedAtNull() {
            TestAuditableEntity entity = new TestAuditableEntity();
            // Only set updatedAt manually
            entity.onUpdate();
            
            // createdAt is null, so wasModified should return false
            assertFalse(entity.wasModified());
        }
        
        @Test
        @DisplayName("Should return false for wasModified when updatedAt is null")
        void shouldReturnFalseWasModifiedWhenUpdatedAtNull() {
            TestAuditableEntity entity = new TestAuditableEntity();
            // Set createdAt manually via reflection
            entity.setCreatedAt(LocalDateTime.now());
            
            // updatedAt is null, so wasModified should return false
            assertFalse(entity.wasModified());
        }
        
        @Test
        @DisplayName("Should get current user from ThreadLocal")
        void shouldGetCurrentUserFromThreadLocal() {
            UUID testUserId = UUID.randomUUID();
            AuditableDataEntity.setCurrentUser(testUserId);
            
            TestAuditableEntity entity = new TestAuditableEntity();
            UUID currentUser = entity.getCurrentUserForTest();
            
            assertEquals(testUserId, currentUser);
        }
        
        @Test
        @DisplayName("Should clear current user")
        void shouldClearCurrentUser() {
            UUID testUserId = UUID.randomUUID();
            AuditableDataEntity.setCurrentUser(testUserId);
            AuditableDataEntity.clearCurrentUser();

            TestAuditableEntity entity = new TestAuditableEntity();
            assertNull(entity.getCurrentUserForTest());
        }
    }

    /**
     * 02-08 Task 3 (T-02-REP-1 / T-02-EOP-4 / T-02-REP-4): pins
     * {@code BaseCommandExecutor}'s current-user wrapper contract by dispatching real
     * {@code onCommand(...)} calls through a mocked {@link BukkitScheduler} that runs the
     * scheduled {@link Runnable} on a thread distinct from the calling thread -- joined before
     * returning. This deliberately defeats a wrapper placed around the *scheduling* call: a
     * {@code ThreadLocal} write made on the calling thread is invisible from a genuinely
     * different thread, so only a wrapper placed inside the handler's own {@code run()} can pass
     * these tests. See the plan's read_first note on this exact trap and 02-08-PLAN.md's
     * cross-phase notice about {@code BaseCommandExecutor.java} being Phase 5's file.
     */
    @Nested
    @DisplayName("BaseCommandExecutor current-user wrapper tests")
    class CurrentUserCommandWrapperTests {

        private Command mockCommand;
        private final UUID[] postRunState = new UUID[1];

        @org.junit.jupiter.api.BeforeEach
        void setUpCommand() {
            mockCommand = mock(Command.class);
            when(mockCommand.getName()).thenReturn("probe");
        }

        private UltiTools stubUltiTools() {
            UltiTools mockUltiTools = mock(UltiTools.class);
            when(mockUltiTools.i18n(anyString())).thenAnswer(inv -> inv.getArgument(0));
            return mockUltiTools;
        }

        /**
         * Runs {@code r} on a brand-new thread (joined before returning), capturing
         * {@link AuditableDataEntity#getCurrentUser()} into {@link #postRunState} on that same
         * thread immediately after {@code r} finishes -- whether it returned normally or
         * {@code BaseCommandExecutor}'s internal catch swallowed an exception from it. Opens its
         * own {@code UltiTools} static mock scope on the new thread, since Mockito's static
         * mocking is thread-scoped and the error-report path in {@code run()} calls
         * {@code UltiTools.getInstance()} from whatever thread the handler runs on.
         */
        private BukkitTask runOnNewThreadCapturingPostState(Runnable r) {
            Thread t = new Thread(() -> {
                try (MockedStatic<UltiTools> inner = mockStatic(UltiTools.class)) {
                    UltiTools mockUltiToolsForThread = stubUltiTools();
                    inner.when(UltiTools::getInstance).thenReturn(mockUltiToolsForThread);
                    r.run();
                } finally {
                    postRunState[0] = AuditableDataEntity.getCurrentUser();
                }
            }, "gsd-02-08-dispatch-thread");
            t.start();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mock(BukkitTask.class);
        }

        private void dispatch(BaseCommandExecutor executor, CommandSender sender, String[] args) {
            BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
            when(mockScheduler.runTask(any(), any(Runnable.class)))
                    .thenAnswer(inv -> runOnNewThreadCapturingPostState(inv.getArgument(1)));
            when(mockScheduler.runTaskAsynchronously(any(), any(Runnable.class)))
                    .thenAnswer(inv -> runOnNewThreadCapturingPostState(inv.getArgument(1)));

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class);
                 MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                UltiTools mockUltiTools = stubUltiTools();
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                executor.onCommand(sender, mockCommand, "probe", args);
            }
        }

        @Test
        @DisplayName("Player-sourced sync command observes the player's UUID, on a thread distinct from the caller")
        void playerSyncCommandObservesUuidOnHandlerThread() {
            CurrentUserProbeExecutor executor = new CurrentUserProbeExecutor();
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);

            dispatch(executor, player, new String[]{});

            assertTrue(executor.invoked, "the probe handler should have run");
            assertEquals(uuid, executor.observedDuringHandler);
            assertNotEquals(Thread.currentThread(), executor.executionThread,
                    "the handler must run on the thread the scheduler actually invokes it on, not the calling thread");
        }

        @Test
        @DisplayName("Console-sourced sync command leaves the current user unset (SQL NULL, not a placeholder)")
        void consoleSyncCommandLeavesUserUnset() {
            CurrentUserProbeExecutor executor = new CurrentUserProbeExecutor();
            CommandSender console = mock(CommandSender.class);

            dispatch(executor, console, new String[]{});

            assertTrue(executor.invoked);
            assertNull(executor.observedDuringHandler);
        }

        @Test
        @DisplayName("@AsyncCommand handler observes the player's UUID on its own dedicated thread")
        void asyncCommandObservesUuidOnHandlerThread() {
            CurrentUserProbeExecutor executor = new CurrentUserProbeExecutor();
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);

            dispatch(executor, player, new String[]{"async"});

            assertTrue(executor.invoked);
            assertEquals(uuid, executor.observedDuringHandler);
        }

        @Test
        @DisplayName("@RunAsync handler observes the player's UUID on its own dedicated thread")
        void runAsyncCommandObservesUuidOnHandlerThread() {
            CurrentUserProbeExecutor executor = new CurrentUserProbeExecutor();
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);

            dispatch(executor, player, new String[]{"runasync"});

            assertTrue(executor.invoked);
            assertEquals(uuid, executor.observedDuringHandler);
        }

        @Test
        @DisplayName("ThreadLocal holds no value on the handler's own thread after it returns normally")
        void threadLocalClearedAfterNormalReturn() {
            CurrentUserProbeExecutor executor = new CurrentUserProbeExecutor();
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());

            dispatch(executor, player, new String[]{});

            assertTrue(executor.invoked);
            assertNull(postRunState[0],
                    "clearCurrentUser() must run on the handler's own thread before it finishes");
        }

        @Test
        @DisplayName("ThreadLocal holds no value on the handler's own thread even when the handler throws")
        void threadLocalClearedAfterHandlerThrows() {
            CurrentUserProbeExecutor executor = new CurrentUserProbeExecutor();
            executor.throwOnInvoke = true;
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);

            dispatch(executor, player, new String[]{});

            assertTrue(executor.invoked, "the handler ran (and observed its user) before throwing");
            assertEquals(uuid, executor.observedDuringHandler);
            assertNull(postRunState[0],
                    "clearCurrentUser() must still run in a finally even though the handler threw");
        }

        @Test
        @DisplayName("A thread reused for a second dispatch does not retain the first dispatch's user")
        void reusedThreadDoesNotLeakBetweenDispatches() throws Exception {
            ExecutorService pooledWorker =
                    Executors.newSingleThreadExecutor(r -> new Thread(r, "gsd-02-08-pooled-worker"));
            try {
                CurrentUserProbeExecutor executorA = new CurrentUserProbeExecutor();
                Player playerA = mock(Player.class);
                UUID uuidA = UUID.randomUUID();
                when(playerA.getUniqueId()).thenReturn(uuidA);
                dispatchOnPool(pooledWorker, executorA, playerA, new String[]{});
                assertEquals(uuidA, executorA.observedDuringHandler);

                CurrentUserProbeExecutor executorB = new CurrentUserProbeExecutor();
                CommandSender console = mock(CommandSender.class);
                dispatchOnPool(pooledWorker, executorB, console, new String[]{});

                assertTrue(executorB.invoked);
                assertNull(executorB.observedDuringHandler,
                        "the pooled thread must not carry executorA's user into executorB's dispatch");
            } finally {
                pooledWorker.shutdownNow();
            }
        }

        private void dispatchOnPool(ExecutorService pool, BaseCommandExecutor executor, CommandSender sender,
                                     String[] args) throws Exception {
            BukkitScheduler mockScheduler = mock(BukkitScheduler.class);
            Answer<BukkitTask> submit = inv -> {
                Runnable r = inv.getArgument(1);
                pool.submit(() -> {
                    try (MockedStatic<UltiTools> inner = mockStatic(UltiTools.class)) {
                        UltiTools mockUltiToolsForThread = stubUltiTools();
                    inner.when(UltiTools::getInstance).thenReturn(mockUltiToolsForThread);
                        r.run();
                    }
                }).get(5, TimeUnit.SECONDS);
                return mock(BukkitTask.class);
            };
            when(mockScheduler.runTask(any(), any(Runnable.class))).thenAnswer(submit);
            when(mockScheduler.runTaskAsynchronously(any(), any(Runnable.class))).thenAnswer(submit);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class);
                 MockedStatic<UltiTools> ultiToolsMock = mockStatic(UltiTools.class)) {
                bukkitMock.when(Bukkit::getScheduler).thenReturn(mockScheduler);
                UltiTools mockUltiTools = stubUltiTools();
                ultiToolsMock.when(UltiTools::getInstance).thenReturn(mockUltiTools);
                executor.onCommand(sender, mockCommand, "probe", args);
            }
        }

        @Test
        @DisplayName("Two concurrent command dispatches on different threads do not observe each other's user")
        void concurrentDispatchesDoNotObserveEachOthersUser() throws InterruptedException {
            CurrentUserProbeExecutor executorA = new CurrentUserProbeExecutor();
            executorA.useLatches = true;
            CurrentUserProbeExecutor executorB = new CurrentUserProbeExecutor();
            executorB.useLatches = true;

            Player playerA = mock(Player.class);
            UUID uuidA = UUID.randomUUID();
            when(playerA.getUniqueId()).thenReturn(uuidA);
            Player playerB = mock(Player.class);
            UUID uuidB = UUID.randomUUID();
            when(playerB.getUniqueId()).thenReturn(uuidB);

            Thread t1 = new Thread(() -> dispatch(executorA, playerA, new String[]{}));
            Thread t2 = new Thread(() -> dispatch(executorB, playerB, new String[]{}));
            t1.start();
            t2.start();

            assertTrue(executorA.entered.await(5, TimeUnit.SECONDS), "executor A should have entered its handler");
            assertTrue(executorB.entered.await(5, TimeUnit.SECONDS), "executor B should have entered its handler");

            // Both handlers are now inside their bodies concurrently -- release them together.
            executorA.release.countDown();
            executorB.release.countDown();

            t1.join(5000);
            t2.join(5000);

            assertEquals(uuidA, executorA.observedDuringHandler);
            assertEquals(uuidB, executorB.observedDuringHandler);
            assertNotEquals(executorA.observedDuringHandler, executorB.observedDuringHandler);
        }
    }

    // Test implementations
    
    static class TestEntity extends BaseDataEntity<UUID> implements Cloneable {
        private String name = "default";
        private boolean onCreateCalled = false;
        private boolean onUpdateCalled = false;
        private boolean onDeleteCalled = false;
        private boolean onLoadCalled = false;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public boolean isOnCreateCalled() { return onCreateCalled; }
        public boolean isOnUpdateCalled() { return onUpdateCalled; }
        public boolean isOnDeleteCalled() { return onDeleteCalled; }
        public boolean isOnLoadCalled() { return onLoadCalled; }
        
        @Override
        public void onCreate() {
            super.onCreate();
            this.onCreateCalled = true;
        }
        
        @Override
        public void onUpdate() {
            super.onUpdate();
            this.onUpdateCalled = true;
        }
        
        @Override
        public void onDelete() {
            super.onDelete();
            this.onDeleteCalled = true;
        }
        
        @Override
        public void onLoad() {
            super.onLoad();
            this.onLoadCalled = true;
        }
        
        @Override
        public boolean validate() {
            return name != null && !name.isEmpty();
        }
        
        @Override
        public List<String> getValidationErrors() {
            List<String> errors = new ArrayList<>();
            if (name == null || name.isEmpty()) {
                errors.add("Name is required");
            }
            return errors;
        }
    }
    
    static class TestLongEntity extends BaseDataEntity<Long> {
        private String value;
        
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
    
    static class TestAuditableEntity extends AuditableDataEntity<UUID> {
        private String data;
        
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        
        // Expose protected method for testing
        public UUID getCurrentUserForTest() {
            return AuditableDataEntity.getCurrentUser();
        }
        
        // Allow setting createdAt for testing
        public void setCreatedAt(java.time.LocalDateTime createdAt) {
            super.setCreatedAt(createdAt);
        }
    }
    
    /**
     * Simple entity without custom validation (uses default implementations).
     */
    static class SimpleEntity extends BaseDataEntity<UUID> {
        // Uses default validate() and getValidationErrors()
    }
    
    /**
     * Entity that supports cloning.
     */
    static class CloneableEntity extends BaseDataEntity<UUID> implements Cloneable {
        private String name;
        private int value;
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
        
        @Override
        protected Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }
    
    /**
     * Entity that does not support cloning (throws exception).
     */
    static class NonCloneableEntity extends BaseDataEntity<UUID> {
        @Override
        protected Object clone() throws CloneNotSupportedException {
            throw new CloneNotSupportedException("Cloning not supported");
        }
    }

    /**
     * Counting fixture shared by {@code SQLiteDataOperatorTest} and
     * {@code SimpleJsonDataOperatorTest} (02-08 Task 1) so both backends assert the same
     * hook-firing counts and order against the same entity shape.
     * <p>
     * Counters and order lists are {@code static} rather than per-instance: the relational
     * backend deserializes every returned row into a brand-new instance via Gson
     * ({@code AbstractRelationalDataOperator#getListHandler()}), which never runs a
     * constructor parameter through the object -- only its no-arg constructor and field
     * initializers -- so a shared list reference handed in via a constructor would be null on
     * every entity Gson produces. Static state is safe here because this project's Surefire
     * configuration has no {@code <parallel>} element (sequential test-class execution is the
     * default), and every consuming test calls {@link #resetCounters()} in its own
     * {@code @BeforeEach}/{@code @AfterEach}.
     */
    @Table("counting_auditable_entity")
    public static class CountingAuditableEntity extends AuditableDataEntity<String> {

        @Column(value = "label", type = "VARCHAR(255)")
        private String label;

        private static final AtomicInteger ON_CREATE_COUNT = new AtomicInteger();
        private static final AtomicInteger ON_UPDATE_COUNT = new AtomicInteger();
        private static final AtomicInteger ON_DELETE_COUNT = new AtomicInteger();
        private static final AtomicInteger ON_LOAD_COUNT = new AtomicInteger();

        private static final List<String> ON_CREATE_ORDER = Collections.synchronizedList(new ArrayList<>());
        private static final List<String> ON_UPDATE_ORDER = Collections.synchronizedList(new ArrayList<>());
        private static final List<String> ON_DELETE_ORDER = Collections.synchronizedList(new ArrayList<>());
        private static final List<String> ON_LOAD_ORDER = Collections.synchronizedList(new ArrayList<>());

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public static void resetCounters() {
            ON_CREATE_COUNT.set(0);
            ON_UPDATE_COUNT.set(0);
            ON_DELETE_COUNT.set(0);
            ON_LOAD_COUNT.set(0);
            ON_CREATE_ORDER.clear();
            ON_UPDATE_ORDER.clear();
            ON_DELETE_ORDER.clear();
            ON_LOAD_ORDER.clear();
        }

        public static int onCreateCount() {
            return ON_CREATE_COUNT.get();
        }

        public static int onUpdateCount() {
            return ON_UPDATE_COUNT.get();
        }

        public static int onDeleteCount() {
            return ON_DELETE_COUNT.get();
        }

        public static int onLoadCount() {
            return ON_LOAD_COUNT.get();
        }

        public static List<String> onCreateOrder() {
            return new ArrayList<>(ON_CREATE_ORDER);
        }

        public static List<String> onUpdateOrder() {
            return new ArrayList<>(ON_UPDATE_ORDER);
        }

        public static List<String> onDeleteOrder() {
            return new ArrayList<>(ON_DELETE_ORDER);
        }

        public static List<String> onLoadOrder() {
            return new ArrayList<>(ON_LOAD_ORDER);
        }

        @Override
        public void onCreate() {
            super.onCreate();
            ON_CREATE_COUNT.incrementAndGet();
            ON_CREATE_ORDER.add(getId());
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            ON_UPDATE_COUNT.incrementAndGet();
            ON_UPDATE_ORDER.add(getId());
        }

        @Override
        public void onDelete() {
            super.onDelete();
            ON_DELETE_COUNT.incrementAndGet();
            ON_DELETE_ORDER.add(getId());
        }

        @Override
        public void onLoad() {
            super.onLoad();
            ON_LOAD_COUNT.incrementAndGet();
            ON_LOAD_ORDER.add(getId());
        }
    }

    /**
     * 02-08 Task 3 fixture: a minimal {@code BaseCommandExecutor} whose handler records
     * {@link AuditableDataEntity#getCurrentUser()} as observed from inside the invocation, plus
     * the thread it ran on. {@code useLatches} lets a test force two concurrent dispatches to be
     * inside their handler bodies at the same instant, proving isolation rather than accidental
     * serialization.
     */
    @CmdTarget(CmdTarget.CmdTargetType.BOTH)
    @CmdExecutor(alias = {"probe"})
    public static class CurrentUserProbeExecutor extends BaseCommandExecutor {
        volatile UUID observedDuringHandler;
        volatile boolean invoked;
        volatile Thread executionThread;
        volatile boolean throwOnInvoke;
        boolean useLatches;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        protected void handleHelp(CommandSender sender) {
            // Not exercised by these tests.
        }

        private void probeBody() {
            observedDuringHandler = AuditableDataEntity.getCurrentUser();
            invoked = true;
            executionThread = Thread.currentThread();
            if (useLatches) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (throwOnInvoke) {
                throw new RuntimeException("boom - 02-08 current-user wrapper test");
            }
        }

        @CmdMapping(format = "")
        public void probe(CommandSender sender) {
            probeBody();
        }

        @CmdMapping(format = "async")
        @AsyncCommand(showProcessing = false, timeout = 0)
        public void probeAsync(CommandSender sender) {
            probeBody();
        }

        @CmdMapping(format = "runasync")
        @RunAsync
        public void probeRunAsync(CommandSender sender) {
            probeBody();
        }
    }
}
