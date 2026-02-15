package com.ultikits.ultitools.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for ClassLoaderUtils to verify protection against malicious class loading.
 * <br>
 * ClassLoaderUtils的安全测试，验证对恶意类加载的防护。
 *
 * @author UltiKits Security Team
 * @version 1.0.0
 */
public class ClassLoaderSecurityTest {
    
    @BeforeEach
    public void setUp() {
        // Initialize security policy if needed
    }
    
    @Test
    public void testBlockDangerousClasses() {
        String[] dangerousClasses = {
            "java.lang.ProcessBuilder",
            "java.lang.Runtime",
            "java.lang.System",
            "javax.script.ScriptEngine",
            "sun.misc.Unsafe"
        };
        
        for (String className : dangerousClasses) {
            assertThrows(SecurityException.class, () -> {
                ClassLoaderUtils.loadClass(className);
            }, "Should block dangerous class: " + className);
        }
    }
    
    @Test
    public void testBlockUntrustedPackages() {
        String[] untrustedClasses = {
            "com.evil.malware.Payload",
            "hacker.tools.Exploit",
            "java.lang.reflect.Method"
        };
        
        for (String className : untrustedClasses) {
            assertThrows(SecurityException.class, () -> {
                ClassLoaderUtils.loadClass(className);
            }, "Should block untrusted package: " + className);
        }
    }
    
    @Test
    public void testAllowTrustedClasses() {
        String[] trustedClasses = {
            "com.ultikits.ultitools.NonExistentTestClass",
            "org.bukkit.plugin.NonExistentPlugin"
        };
        
        for (String className : trustedClasses) {
            // These should not throw SecurityException
            // Note: ClassNotFoundException is expected since these classes don't exist
            try {
                ClassLoaderUtils.loadClass(className);
                fail("Expected ClassNotFoundException for: " + className);
            } catch (SecurityException e) {
                fail("Should not block trusted class: " + className);
            } catch (ClassNotFoundException e) {
                // Expected - class doesn't exist, but passed security check
                assertTrue(true);
            }
        }
    }
    
    @Test
    public void testBlockSuspiciousClassNames() {
        String[] suspiciousClasses = {
            "com.ultikits.ultitools.ProcessHelper",
            "com.ultikits.ultitools.RuntimeManager",
            "com.ultikits.ultitools.ScriptExecutor"
        };
        
        for (String className : suspiciousClasses) {
            assertThrows(SecurityException.class, () -> {
                ClassLoaderUtils.loadClass(className);
            }, "Should block suspicious class name: " + className);
        }
    }
    
    @Test
    public void testValidateNullAndEmptyClassNames() {
        assertThrows(SecurityException.class, () -> {
            ClassLoaderUtils.loadClass(null);
        }, "Should block null class name");
        
        assertThrows(SecurityException.class, () -> {
            ClassLoaderUtils.loadClass("");
        }, "Should block empty class name");
        
        assertThrows(SecurityException.class, () -> {
            ClassLoaderUtils.loadClass("   ");
        }, "Should block whitespace-only class name");
    }
    
    @Test
    public void testValidateInvalidClassNameFormats() {
        String[] invalidFormats = {
            "123InvalidStart",
            "com..double.dot",
            "com.invalid-dash.Class",
            "com.invalid space.Class",
            "com.invalid$special$.Class"
        };
        
        for (String className : invalidFormats) {
            assertThrows(SecurityException.class, () -> {
                ClassLoaderUtils.loadClass(className);
            }, "Should block invalid format: " + className);
        }
    }
    
    @Test
    public void testSecurityPolicyConfiguration() {
        // Test adding trusted packages
        SecurityPolicy.addTrustedPackage("com.example.trusted");
        assertTrue(SecurityPolicy.isSafeClassName("com.example.trusted.TestClass"));
        
        // Test adding dangerous classes
        SecurityPolicy.addDangerousClass("com.example.dangerous.BadClass");
        assertFalse(SecurityPolicy.isSafeClassName("com.example.dangerous.BadClass"));
    }
    
    @Test
    public void testParameterTypeSafety() {
        // Safe parameter types
        assertTrue(SecurityPolicy.isSafeParameterType(String.class));
        assertTrue(SecurityPolicy.isSafeParameterType(Integer.class));
        assertTrue(SecurityPolicy.isSafeParameterType(java.util.List.class));
        
        // Unsafe parameter types would need to be tested with reflection
        // This is a simplified test
    }
    
    @Test
    public void testFileStructureValidation() {
        // Test file size and entry count limits
        assertTrue(SecurityPolicy.isSafeFileStructure(1024 * 1024, 100)); // 1MB, 100 entries
        assertFalse(SecurityPolicy.isSafeFileStructure(200 * 1024 * 1024, 100)); // 200MB - too large
        assertFalse(SecurityPolicy.isSafeFileStructure(1024 * 1024, 20000)); // Too many entries
    }
}
