package com.ultikits.ultitools.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PluginModuleException.
 */
@DisplayName("PluginModuleException Tests")
class PluginModuleExceptionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor with message should use PLUGIN_ERROR code")
        void constructor_withMessage_usesPluginErrorCode() {
            PluginModuleException exception = new PluginModuleException("Test message");
            
            assertEquals(ErrorCode.PLUGIN_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with error code and message should use specified code")
        void constructor_withErrorCodeAndMessage_usesSpecifiedCode() {
            PluginModuleException exception = new PluginModuleException(ErrorCode.PLUGIN_LOAD_FAILED, "Load failed");
            
            assertEquals(ErrorCode.PLUGIN_LOAD_FAILED, exception.getErrorCode());
            assertEquals("Load failed", exception.getMessage());
        }

        @Test
        @DisplayName("Constructor with message and cause should set both")
        void constructor_withMessageAndCause_setsBoth() {
            Throwable cause = new RuntimeException("Root cause");
            PluginModuleException exception = new PluginModuleException("Test message", cause);
            
            assertEquals(ErrorCode.PLUGIN_ERROR, exception.getErrorCode());
            assertEquals("Test message", exception.getMessage());
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("Constructor with error code, message and cause should set all")
        void constructor_withErrorCodeMessageAndCause_setsAll() {
            Throwable cause = new RuntimeException("Root cause");
            PluginModuleException exception = new PluginModuleException(ErrorCode.PLUGIN_UNLOAD_FAILED, "Unload failed", cause);
            
            assertEquals(ErrorCode.PLUGIN_UNLOAD_FAILED, exception.getErrorCode());
            assertEquals("Unload failed", exception.getMessage());
            assertSame(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("Static Factory Method Tests")
    class StaticFactoryMethodTests {

        @Test
        @DisplayName("loadFailed should create exception with correct code and message")
        void loadFailed_createsCorrectException() {
            Throwable cause = new RuntimeException("Class not found");
            PluginModuleException exception = PluginModuleException.loadFailed("MyPlugin", cause);
            
            assertEquals(ErrorCode.PLUGIN_LOAD_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("MyPlugin"));
            assertTrue(exception.getMessage().contains("Failed to load"));
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("unloadFailed should create exception with correct code and message")
        void unloadFailed_createsCorrectException() {
            Throwable cause = new RuntimeException("Resource busy");
            PluginModuleException exception = PluginModuleException.unloadFailed("MyPlugin", cause);
            
            assertEquals(ErrorCode.PLUGIN_UNLOAD_FAILED, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("MyPlugin"));
            assertTrue(exception.getMessage().contains("Failed to unload"));
            assertSame(cause, exception.getCause());
        }

        @Test
        @DisplayName("dependencyMissing should create exception with correct code and message")
        void dependencyMissing_createsCorrectException() {
            PluginModuleException exception = PluginModuleException.dependencyMissing("MyPlugin", "RequiredLib");
            
            assertEquals(ErrorCode.PLUGIN_DEPENDENCY_ERROR, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("MyPlugin"));
            assertTrue(exception.getMessage().contains("RequiredLib"));
            assertTrue(exception.getMessage().contains("missing dependency"));
        }

        @Test
        @DisplayName("circularDependency should create exception with correct code and message")
        void circularDependency_createsCorrectException() {
            PluginModuleException exception = PluginModuleException.circularDependency("PluginA", "PluginB", "PluginA");
            
            assertEquals(ErrorCode.PLUGIN_CIRCULAR_DEPENDENCY, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("PluginA"));
            assertTrue(exception.getMessage().contains("PluginB"));
            assertTrue(exception.getMessage().contains("Circular dependency"));
        }

        @Test
        @DisplayName("circularDependency with single plugin")
        void circularDependency_withSinglePlugin() {
            PluginModuleException exception = PluginModuleException.circularDependency("SelfRefPlugin");
            
            assertEquals(ErrorCode.PLUGIN_CIRCULAR_DEPENDENCY, exception.getErrorCode());
            assertTrue(exception.getMessage().contains("SelfRefPlugin"));
        }

        @Test
        @DisplayName("circularDependency with multiple plugins")
        void circularDependency_withMultiplePlugins() {
            PluginModuleException exception = PluginModuleException.circularDependency("A", "B", "C", "D", "A");
            
            String message = exception.getMessage();
            assertTrue(message.contains("A"));
            assertTrue(message.contains("B"));
            assertTrue(message.contains("C"));
            assertTrue(message.contains("D"));
            assertTrue(message.contains("->"));
        }
    }

    @Nested
    @DisplayName("Inherited Method Tests")
    class InheritedMethodTests {

        @Test
        @DisplayName("getFormattedMessage should include error code prefix")
        void getFormattedMessage_includesErrorCodePrefix() {
            PluginModuleException exception = new PluginModuleException("Test");
            
            String formatted = exception.getFormattedMessage();
            assertTrue(formatted.contains("ULTI-"));
            assertTrue(formatted.contains("Test"));
        }

        @Test
        @DisplayName("toString should include class name and formatted message")
        void toString_includesClassNameAndFormattedMessage() {
            PluginModuleException exception = new PluginModuleException("Test message");
            
            String str = exception.toString();
            assertTrue(str.contains("PluginModuleException"));
            assertTrue(str.contains("ULTI-"));
            assertTrue(str.contains("Test message"));
        }

        @Test
        @DisplayName("getErrorCode should return correct error code")
        void getErrorCode_returnsCorrectErrorCode() {
            PluginModuleException exception = new PluginModuleException(ErrorCode.PLUGIN_DEPENDENCY_ERROR, "Dep error");
            
            assertEquals(ErrorCode.PLUGIN_DEPENDENCY_ERROR, exception.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Exception Hierarchy Tests")
    class ExceptionHierarchyTests {

        @Test
        @DisplayName("PluginModuleException should extend UltiToolsException")
        void pluginModuleException_extendsUltiToolsException() {
            PluginModuleException exception = new PluginModuleException("Test");
            
            assertTrue(exception instanceof UltiToolsException);
        }

        @Test
        @DisplayName("PluginModuleException should extend RuntimeException")
        void pluginModuleException_extendsRuntimeException() {
            PluginModuleException exception = new PluginModuleException("Test");
            
            assertTrue(exception instanceof RuntimeException);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty message should be handled")
        void emptyMessage_isHandled() {
            PluginModuleException exception = new PluginModuleException("");
            
            assertEquals("", exception.getMessage());
            assertNotNull(exception.getErrorCode());
        }

        @Test
        @DisplayName("loadFailed with empty plugin name")
        void loadFailed_withEmptyPluginName() {
            Throwable cause = new RuntimeException("Error");
            PluginModuleException exception = PluginModuleException.loadFailed("", cause);
            
            assertEquals(ErrorCode.PLUGIN_LOAD_FAILED, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("unloadFailed with empty plugin name")
        void unloadFailed_withEmptyPluginName() {
            Throwable cause = new RuntimeException("Error");
            PluginModuleException exception = PluginModuleException.unloadFailed("", cause);
            
            assertEquals(ErrorCode.PLUGIN_UNLOAD_FAILED, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("dependencyMissing with empty names")
        void dependencyMissing_withEmptyNames() {
            PluginModuleException exception = PluginModuleException.dependencyMissing("", "");
            
            assertEquals(ErrorCode.PLUGIN_DEPENDENCY_ERROR, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }

        @Test
        @DisplayName("circularDependency with no plugins")
        void circularDependency_withNoPlugins() {
            PluginModuleException exception = PluginModuleException.circularDependency();
            
            assertEquals(ErrorCode.PLUGIN_CIRCULAR_DEPENDENCY, exception.getErrorCode());
            assertNotNull(exception.getMessage());
        }
    }
}
