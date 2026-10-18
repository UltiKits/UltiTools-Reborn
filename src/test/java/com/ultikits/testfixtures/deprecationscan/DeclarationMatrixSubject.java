package com.ultikits.testfixtures.deprecationscan;

import java.util.Date;
import java.util.List;
import java.util.Map.Entry;

/** Compiled declarations used as data by the build-only scanner tests. */
@Deprecated(since = "6.2.0")
public class DeclarationMatrixSubject<T extends Number & Comparable<T>, U extends T> {

    @Deprecated(since = "6.2.0")
    public void annotationOnly() {
        // Fixture body: only the declaration is scanner input.
    }

    /** Ordinary documentation without a deprecated block tag. */
    @Deprecated(since = "6.2.1")
    public void ordinaryDoc() {
        // Fixture body: only the declaration is scanner input.
    }

    /**
     * @deprecated Use the replacement field.
     * @removeIn 7.0.0
     */
    // Placed after methods on purpose: the scanner must find fields anywhere in the member list.
    @SuppressWarnings("PMD.FieldDeclarationsShouldBeAtStartOfClass")
    @Deprecated(since = "6.2.2")
    public String initializedField = "(";

    // After methods on purpose, and a constructor-call initializer whose argument holds ';' and '('.
    @SuppressWarnings({"PMD.FieldDeclarationsShouldBeAtStartOfClass", "PMD.StringInstantiation"})
    @Deprecated
    public String constructorField = new String("a;b(");

    // Placed after methods on purpose: the scanner must find fields anywhere in the member list.
    @SuppressWarnings("PMD.FieldDeclarationsShouldBeAtStartOfClass")
    @Deprecated
    public String factoryField = String.valueOf(1);

    // Placed after methods on purpose: the scanner must find fields anywhere in the member list.
    @SuppressWarnings("PMD.FieldDeclarationsShouldBeAtStartOfClass")
    @Deprecated
    public int[] arrayField = {1, 2};

    /**
     * @deprecated Use {@link java.lang.String} instead.<br>Secondary prose.
     * @removeIn 7.0.0
     */
    @Deprecated(since = "6.2.3", forRemoval = true)
    public void documented() {
        // Fixture body: only the declaration is scanner input.
    }

    /** Inline {@code @deprecated fake} and {@code @removeIn 1.0.0} are not block tags. */
    @Deprecated
    public void inlineProse() {
        // Fixture body: only the declaration is scanner input.
    }

    @Deprecated
    public DeclarationMatrixSubject() {
        // Fixture body: only the declaration is scanner input.
    }

    // The unused parameters are the signature under test (reference and varargs erasure).
    @SuppressWarnings("PMD.UnusedFormalParameter")
    @Deprecated
    public DeclarationMatrixSubject(String name, int... flags) {
        // Fixture body: only the declaration is scanner input.
    }

    @Deprecated
    public void overload(Date value) {
        // Fixture body: only the declaration is scanner input.
    }

    @Deprecated
    public void overload(java.sql.Date value) {
        // Fixture body: only the declaration is scanner input.
    }

    @Deprecated
    public void arrays(int[][] primitives, String[] references, T[] generic, Entry<String, String>... entries) {
        // Fixture body: only the declaration is scanner input.
    }

    @Deprecated
    public <T extends CharSequence> void shadow(T value, U[] outerBound) {
        // Fixture body: only the declaration is scanner input.
    }

    @Deprecated
    public <V> void unbounded(V value, V[] array) {
        // Fixture body: only the declaration is scanner input.
    }

    @Deprecated
    public void parameterized(List<? extends Number> values, Named.InnerType nested) {
        // Fixture body: only the declaration is scanner input.
    }

    public void localDeclarationsAreNotApi() {
        // Unused locals on purpose: local declarations must never become registry rows.
        @SuppressWarnings("PMD.UnusedLocalVariable")
        @Deprecated String local = "ignored";
        @Deprecated class Local {
            @Deprecated public void hidden() {
                // Fixture body: a local class member is not API.
            }
        }
        // Unused on purpose: an anonymous class member must never become a registry row.
        @SuppressWarnings("PMD.UnusedLocalVariable")
        Object anonymous = new Object() {
            @Deprecated public void hidden() {
                // Fixture body: an anonymous class member is not API.
            }
        };
    }

    @Deprecated
    public static class Named {
        // Explicit on purpose: the deprecated no-argument constructor is a scanned declaration.
        @SuppressWarnings("PMD.UnnecessaryConstructor")
        @Deprecated public Named() {
            // Fixture body: only the declaration is scanner input.
        }

        @Deprecated public void nested(InnerType value) {
            // Fixture body: only the declaration is scanner input.
        }

        public static class InnerType {
        }
    }

    public class Inner {
        // The unused parameter is the signature under test (hidden outer-instance parameter).
        @SuppressWarnings("PMD.UnusedFormalParameter")
        @Deprecated public Inner(String value) {
            // Fixture body: only the declaration is scanner input.
        }
    }

    public static class Shadows {
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
        public @interface Deprecated {
        }

        /** @deprecated Custom same-spelled annotation must not create a registry row. */
        @Deprecated public void custom() {
            // Fixture body: only the declaration is scanner input.
        }

        // The qualified name is required: the simple name resolves to the nested Deprecated above.
        @SuppressWarnings("PMD.UnnecessaryFullyQualifiedName")
        @java.lang.Deprecated public void real() {
            // Fixture body: only the declaration is scanner input.
        }
    }
}
