package com.ultikits.ultitools.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for ClassLoaderUtils to verify protection against malicious class loading.
 * <br>
 * ClassLoaderUtils的安全测试，验证对恶意类加载的防护。
 *
 * <p><b>GEN-07 (6.3.0):</b> {@code SecurityPolicy}'s three name-based filter layers were removed
 * (D-12/D-13). {@code ClassLoaderUtils.loadClass} no longer throws {@link SecurityException} for a
 * dangerous, untrusted, or keyword-matching class name -- only for one that fails
 * {@code VALID_CLASS_NAME_PATTERN}'s format check, which is the only thing still rejecting
 * anything here. The tests below are INVERTED to assert "no longer blocked", not deleted.
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
    public void testFormerlyDangerousClassesAreNoLongerBlocked() {
        String[] formerlyDangerousClasses = {
            "java.lang.ProcessBuilder",
            "java.lang.Runtime",
            "java.lang.System",
            "javax.script.ScriptEngine",
            "sun.misc.Unsafe"
        };

        for (String className : formerlyDangerousClasses) {
            assertDoesNotThrow(() -> {
                try {
                    ClassLoaderUtils.loadClass(className);
                } catch (ClassNotFoundException e) {
                    // Expected for some of these on some JDKs/module systems -- the point is that
                    // SecurityException (the removed exact-name blacklist layer) is never thrown.
                }
            }, "GEN-07: exact-name blacklist layer removed, should not throw SecurityException: " + className);
        }
    }

    @Test
    public void testFormerlyUntrustedPackagesAreNoLongerBlocked() {
        String[] formerlyUntrustedClasses = {
            "com.evil.malware.Payload",
            "hacker.tools.Exploit",
            "java.lang.reflect.Method"
        };

        for (String className : formerlyUntrustedClasses) {
            assertDoesNotThrow(() -> {
                try {
                    ClassLoaderUtils.loadClass(className);
                } catch (ClassNotFoundException e) {
                    // Expected for the two fictitious packages -- the point is that
                    // SecurityException (the removed whitelist layer) is never thrown.
                }
            }, "GEN-07: trusted-package whitelist layer removed, should not throw SecurityException: " + className);
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
    public void testFormerlySuspiciousClassNamesAreNoLongerBlocked() {
        String[] formerlySuspiciousClasses = {
            "com.ultikits.ultitools.ProcessHelper",
            "com.ultikits.ultitools.RuntimeManager",
            "com.ultikits.ultitools.ScriptExecutor"
        };

        for (String className : formerlySuspiciousClasses) {
            try {
                ClassLoaderUtils.loadClass(className);
                fail("Expected ClassNotFoundException for: " + className);
            } catch (SecurityException e) {
                fail("GEN-07: suspicious-keyword layer removed, should not block: " + className);
            } catch (ClassNotFoundException e) {
                // Expected - class doesn't exist on the test classpath, but passed the (now
                // format-only) security check.
                assertTrue(true);
            }
        }
    }

    @Test
    public void testValidateNullAndEmptyClassNames() {
        // GEN-07 (D-13): SecurityPolicy.isSafeClassName no longer gates this. These three still
        // throw SecurityException, but now solely because ClassLoaderUtils.validateClassName's own
        // VALID_CLASS_NAME_PATTERN format regex rejects them -- the removed filter is not why any
        // more (Test 9). A null name is explicitly guarded before the regex runs, so it throws
        // SecurityException rather than leaking a NullPointerException.
        assertThrows(SecurityException.class, () -> {
            ClassLoaderUtils.loadClass(null);
        }, "Should reject null class name as an invalid format");

        assertThrows(SecurityException.class, () -> {
            ClassLoaderUtils.loadClass("");
        }, "Should reject empty class name as an invalid format");

        assertThrows(SecurityException.class, () -> {
            ClassLoaderUtils.loadClass("   ");
        }, "Should reject whitespace-only class name as an invalid format");
    }

    @Test
    public void testValidateInvalidClassNameFormats() {
        // Unaffected by GEN-07 -- VALID_CLASS_NAME_PATTERN is untouched.
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
    public void testSecurityPolicyConfigurationIsNowANoOp() {
        // GEN-07: addTrustedPackage/addDangerousClass no longer mutate anything, and
        // isSafeClassName is unconditionally true regardless of either call.
        SecurityPolicy.addTrustedPackage("com.example.trusted");
        assertTrue(SecurityPolicy.isSafeClassName("com.example.trusted.TestClass"));

        SecurityPolicy.addDangerousClass("com.example.dangerous.BadClass");
        assertTrue(SecurityPolicy.isSafeClassName("com.example.dangerous.BadClass"),
                "GEN-07: addDangerousClass is a no-op, isSafeClassName stays true");
    }

    @Test
    public void testParameterTypeSafety() {
        // Safe parameter types
        assertTrue(SecurityPolicy.isSafeParameterType(String.class));
        assertTrue(SecurityPolicy.isSafeParameterType(Integer.class));
        assertTrue(SecurityPolicy.isSafeParameterType(java.util.List.class));

        // GEN-07: isSafeParameterType is now unconditionally true, including for a type the
        // pre-6.3.0 whitelist would have refused.
        assertTrue(SecurityPolicy.isSafeParameterType(ProcessBuilder.class));
    }

    @Test
    public void testFileStructureValidation() {
        // Test file size and entry count limits
        assertTrue(SecurityPolicy.isSafeFileStructure(1024 * 1024, 100)); // 1MB, 100 entries
        assertFalse(SecurityPolicy.isSafeFileStructure(200 * 1024 * 1024, 100)); // 200MB - too large
        assertFalse(SecurityPolicy.isSafeFileStructure(1024 * 1024, 20000)); // Too many entries
    }
}
