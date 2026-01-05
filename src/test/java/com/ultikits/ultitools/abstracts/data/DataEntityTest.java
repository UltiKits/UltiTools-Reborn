package com.ultikits.ultitools.abstracts.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BaseDataEntity and AuditableDataEntity.
 */
@DisplayName("Data Entity Tests")
class DataEntityTest {

    @AfterEach
    void tearDown() {
        // Always clear current user after each test
        AuditableDataEntity.clearCurrentUser();
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
        public java.util.List<String> getValidationErrors() {
            java.util.List<String> errors = new java.util.ArrayList<>();
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
}
