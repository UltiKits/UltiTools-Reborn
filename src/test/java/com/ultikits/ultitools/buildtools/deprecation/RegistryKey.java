package com.ultikits.ultitools.buildtools.deprecation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The single identifier space D-22 defines, serving four jobs at once: registry primary key,
 * japicmp {@code <exclude>} entry, D-01's join key between the registry and the japicmp report,
 * and D-21's allowlist key.
 *
 * <p>Its string form is exactly japicmp's own filter syntax:
 * <ul>
 *   <li>class-level — the bare fully-qualified class name, no {@code #}, no parens.</li>
 *   <li>method/constructor member — {@code package.Class#method(fully.qualified.Param,...)},
 *       exactly one {@code #} and one {@code (}…{@code )}, even for a zero-argument member
 *       (empty parens, never a bare name).</li>
 *   <li>field member — {@code package.Class#fieldName}, no parens at all (japicmp's separate
 *       field-filter syntax, confirmed by decompiling {@code JavadocLikeFieldFilter} — see
 *       07-JAPICMP-BASELINE.md).</li>
 * </ul>
 */
public final class RegistryKey implements Comparable<RegistryKey> {

    private final String className;
    private final String memberName;
    private final List<String> parameterTypes;

    private RegistryKey(String className, String memberName, List<String> parameterTypes) {
        this.className = Objects.requireNonNull(className, "className");
        this.memberName = memberName;
        this.parameterTypes = parameterTypes;
    }

    /** A whole-class removal key: the bare fully-qualified class name. */
    public static RegistryKey forClass(String className) {
        return new RegistryKey(className, null, null);
    }

    /**
     * A method or constructor member key. {@code parameterTypes} must be fully-qualified (no
     * simple-name fallback — japicmp's filter parser requires it) and may be empty for a
     * zero-argument member, but never {@code null}.
     */
    public static RegistryKey forMember(String className, String memberName, List<String> parameterTypes) {
        Objects.requireNonNull(memberName, "memberName");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        return new RegistryKey(className, memberName, Collections.unmodifiableList(new ArrayList<>(parameterTypes)));
    }

    /** A field member key — no parameter list at all, matching japicmp's field-filter syntax. */
    public static RegistryKey forField(String className, String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        return new RegistryKey(className, fieldName, null);
    }

    public boolean isClassLevel() {
        return memberName == null;
    }

    public boolean isField() {
        return memberName != null && parameterTypes == null;
    }

    public String getClassName() {
        return className;
    }

    public String getMemberName() {
        return memberName;
    }

    public List<String> getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public String toString() {
        if (memberName == null) {
            return className;
        }
        if (parameterTypes == null) {
            return className + "#" + memberName;
        }
        return className + "#" + memberName + "(" + String.join(",", parameterTypes) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RegistryKey)) {
            return false;
        }
        return toString().equals(o.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public int compareTo(RegistryKey other) {
        return toString().compareTo(other.toString());
    }
}
