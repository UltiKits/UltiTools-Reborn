package com.ultikits.ultitools.interfaces.impl.logger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.hutool.log.Log;

class BukkitLogFactoryTest {

    private BukkitLogFactory factory;

    @BeforeEach
    void setUp() {
        factory = new BukkitLogFactory();
    }

    // ==================== Constructor Tests ====================
    
    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        
        @Test
        @DisplayName("Should create factory with correct name")
        void testFactoryCreation() {
            BukkitLogFactory newFactory = new BukkitLogFactory();
            assertThat(newFactory).isNotNull();
        }
    }

    // ==================== createLog(String) Tests ====================
    
    @Nested
    @DisplayName("createLog(String) Tests")
    class CreateLogWithStringTests {
        
        @Test
        @DisplayName("Should create BukkitLog with string name")
        void testCreateLogWithString() {
            Log log = factory.createLog("TestLogger");
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("TestLogger");
        }
        
        @Test
        @DisplayName("Should create BukkitLog with empty string name")
        void testCreateLogWithEmptyString() {
            Log log = factory.createLog("");
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEmpty();
        }
        
        @Test
        @DisplayName("Should create BukkitLog with null string name")
        void testCreateLogWithNullString() {
            Log log = factory.createLog((String) null);
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isNull();
        }
        
        @Test
        @DisplayName("Should create BukkitLog with special characters in name")
        void testCreateLogWithSpecialChars() {
            Log log = factory.createLog("com.example.MyClass$Inner");
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("com.example.MyClass$Inner");
        }
        
        @Test
        @DisplayName("Should create BukkitLog with unicode name")
        void testCreateLogWithUnicodeName() {
            Log log = factory.createLog("日志记录器");
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("日志记录器");
        }
    }

    // ==================== createLog(Class) Tests ====================
    
    @Nested
    @DisplayName("createLog(Class) Tests")
    class CreateLogWithClassTests {
        
        @Test
        @DisplayName("Should create BukkitLog with class")
        void testCreateLogWithClass() {
            Log log = factory.createLog(BukkitLogFactoryTest.class);
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo(BukkitLogFactoryTest.class.getName());
        }
        
        @Test
        @DisplayName("Should create BukkitLog with null class")
        void testCreateLogWithNullClass() {
            Log log = factory.createLog((Class<?>) null);
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("null");
        }
        
        @Test
        @DisplayName("Should create BukkitLog with inner class")
        void testCreateLogWithInnerClass() {
            Log log = factory.createLog(CreateLogWithClassTests.class);
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).contains("BukkitLogFactoryTest$CreateLogWithClassTests");
        }
        
        @Test
        @DisplayName("Should create BukkitLog with anonymous class")
        void testCreateLogWithAnonymousClass() {
            Runnable anonymous = new Runnable() {
                @Override
                public void run() {}
            };
            Log log = factory.createLog(anonymous.getClass());
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).contains("BukkitLogFactoryTest");
        }
        
        @Test
        @DisplayName("Should create BukkitLog with primitive wrapper class")
        void testCreateLogWithPrimitiveWrapper() {
            Log log = factory.createLog(Integer.class);
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("java.lang.Integer");
        }
        
        @Test
        @DisplayName("Should create BukkitLog with array class")
        void testCreateLogWithArrayClass() {
            Log log = factory.createLog(String[].class);
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("[Ljava.lang.String;");
        }
    }

    // ==================== Multiple Instance Tests ====================
    
    @Nested
    @DisplayName("Multiple Instance Tests")
    class MultipleInstanceTests {
        
        @Test
        @DisplayName("Should create independent log instances")
        void testIndependentInstances() {
            Log log1 = factory.createLog("Logger1");
            Log log2 = factory.createLog("Logger2");
            
            assertThat(log1).isNotSameAs(log2);
            assertThat(log1.getName()).isNotEqualTo(log2.getName());
        }
        
        @Test
        @DisplayName("Should create new instance for same name")
        void testNewInstanceForSameName() {
            Log log1 = factory.createLog("SameName");
            Log log2 = factory.createLog("SameName");
            
            // Factory creates new instances each time
            assertThat(log1).isNotSameAs(log2);
            assertThat(log1.getName()).isEqualTo(log2.getName());
        }
        
        @Test
        @DisplayName("Should create new instance for same class")
        void testNewInstanceForSameClass() {
            Log log1 = factory.createLog(String.class);
            Log log2 = factory.createLog(String.class);
            
            // Factory creates new instances each time
            assertThat(log1).isNotSameAs(log2);
            assertThat(log1.getName()).isEqualTo(log2.getName());
        }
    }

    // ==================== Edge Cases ====================
    
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {
        
        @Test
        @DisplayName("Should handle very long logger name")
        void testVeryLongName() {
            String longName = "a".repeat(1000);
            Log log = factory.createLog(longName);
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo(longName);
        }
        
        @Test
        @DisplayName("Should handle name with newlines")
        void testNameWithNewlines() {
            Log log = factory.createLog("Line1\nLine2");
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("Line1\nLine2");
        }
        
        @Test
        @DisplayName("Should handle name with tabs")
        void testNameWithTabs() {
            Log log = factory.createLog("Tab\tSeparated");
            
            assertThat(log).isInstanceOf(BukkitLog.class);
            assertThat(log.getName()).isEqualTo("Tab\tSeparated");
        }
    }
}
