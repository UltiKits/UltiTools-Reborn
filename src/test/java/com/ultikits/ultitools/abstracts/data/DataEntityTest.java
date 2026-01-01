package com.ultikits.ultitools.abstracts.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BaseDataEntity and AuditableDataEntity.
 */
@DisplayName("Data Entity Tests")
class DataEntityTest {

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
    }

    // Test implementations
    
    static class TestEntity extends BaseDataEntity<UUID> {
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
    }
}
