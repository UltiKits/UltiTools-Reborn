package com.ultikits.testfixtures.deprecationscan;

import java.util.Date;
import java.util.List;
import java.util.Map.Entry;

/** Compiled declarations used as data by the build-only scanner tests. */
@Deprecated(since = "6.2.0")
public class DeclarationMatrixSubject<T extends Number & Comparable<T>, U extends T> {

    @Deprecated(since = "6.2.0")
    public void annotationOnly() {
    }

    /** Ordinary documentation without a deprecated block tag. */
    @Deprecated(since = "6.2.1")
    public void ordinaryDoc() {
    }

    /**
     * @deprecated Use the replacement field.
     * @removeIn 7.0.0
     */
    @Deprecated(since = "6.2.2")
    public String initializedField = "(";

    @Deprecated
    public String constructorField = new String("a;b(");

    @Deprecated
    public String factoryField = String.valueOf(1);

    @Deprecated
    public int[] arrayField = {1, 2};

    /**
     * @deprecated Use {@link java.lang.String} instead.<br>Secondary prose.
     * @removeIn 7.0.0
     */
    @Deprecated(since = "6.2.3", forRemoval = true)
    public void documented() {
    }

    /** Inline {@code @deprecated fake} and {@code @removeIn 1.0.0} are not block tags. */
    @Deprecated
    public void inlineProse() {
    }

    @Deprecated
    public DeclarationMatrixSubject() {
    }

    @Deprecated
    public DeclarationMatrixSubject(String name, int... flags) {
    }

    @Deprecated
    public void overload(Date value) {
    }

    @Deprecated
    public void overload(java.sql.Date value) {
    }

    @Deprecated
    public void arrays(int[][] primitives, String[] references, T[] generic, Entry<String, String>... entries) {
    }

    @Deprecated
    public <T extends CharSequence> void shadow(T value, U[] outerBound) {
    }

    @Deprecated
    public <V> void unbounded(V value, V[] array) {
    }

    @Deprecated
    public void parameterized(List<? extends Number> values, Named.InnerType nested) {
    }

    public void localDeclarationsAreNotApi() {
        @Deprecated String local = "ignored";
        @Deprecated class Local {
            @Deprecated public void hidden() {
            }
        }
        Object anonymous = new Object() {
            @Deprecated public void hidden() {
            }
        };
    }

    @Deprecated
    public static class Named {
        @Deprecated public Named() {
        }

        @Deprecated public void nested(InnerType value) {
        }

        public static class InnerType {
        }
    }

    public class Inner {
        @Deprecated public Inner(String value) {
        }
    }

    public static class Shadows {
        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
        public @interface Deprecated {
        }

        /** @deprecated Custom same-spelled annotation must not create a registry row. */
        @Deprecated public void custom() {
        }

        @java.lang.Deprecated public void real() {
        }
    }
}
