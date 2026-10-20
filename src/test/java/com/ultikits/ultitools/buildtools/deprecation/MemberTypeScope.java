package com.ultikits.ultitools.buildtools.deprecation;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simple-name lookup of the member types of one lexical class scope, following JLS 8.5 and 6.4.1.
 *
 * <p>Within a class body the scope of a member type includes every member type of that class: the
 * ones it declares and the ones it inherits from its direct superclass and direct superinterfaces
 * (JLS 8.5, 8.1.6), transitively, since a supertype's member types include what it inherits in
 * turn. A declared member type hides a same-named member type that would otherwise be inherited
 * (JLS 8.5), and a member type in scope in an inner class body shadows same-named types of an
 * enclosing class, a single-type import or the package (JLS 6.4.1); the caller walks the lexical
 * scopes from innermost outward. Private member types are never inherited, and package-private ones
 * only into the same package (JLS 8.5, 6.6). Two distinct inherited member types with one name are
 * ambiguous (JLS 8.5): both are returned, so the caller's correspondence check fails closed.
 *
 * <p>Classes are only loaded, never initialised: the loader is the scanner's
 * {@code Class.forName(name, false, loader)}, and only class-file metadata is read
 * ({@code getSuperclass}, {@code getInterfaces}, {@code getDeclaringClass}, {@code getModifiers}).
 */
final class MemberTypeScope {
    private final Loader loader;
    private final Map<Class<?>, Map<String, Set<Class<?>>>> members = new HashMap<>();

    MemberTypeScope(Loader loader) {
        this.loader = loader;
    }

    /**
     * Binary names of the member types named {@code name} of the compiled class {@code ownerBinary}:
     * its declared member type, else the member types it inherits; empty when it has none.
     */
    Set<String> find(String ownerBinary, String name) {
        Class<?> owner;
        try {
            owner = loader.load(ownerBinary);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Compiled owner unavailable for member type lookup: " + ownerBinary, e);
        }
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> member : membersNamed(owner, name)) {
            names.add(member.getName());
        }
        return names;
    }

    private Set<Class<?>> membersNamed(Class<?> type, String name) {
        Map<String, Set<Class<?>>> byName = members.computeIfAbsent(type, key -> new HashMap<>());
        Set<Class<?>> cached = byName.get(name);
        if (cached == null) {
            cached = computeMembers(type, name);
            byName.put(name, cached);
        }
        return cached;
    }

    private Set<Class<?>> computeMembers(Class<?> type, String name) {
        Class<?> declared = declared(type, name);
        if (declared != null) {
            return Collections.singleton(declared);
        }
        Set<Class<?>> inherited = new LinkedHashSet<>();
        for (Class<?> supertype : directSupertypes(type)) {
            for (Class<?> member : membersNamed(supertype, name)) {
                if (inheritable(member, type)) {
                    inherited.add(member);
                }
            }
        }
        return inherited;
    }

    private Class<?> declared(Class<?> type, String name) {
        try {
            Class<?> candidate = loader.load(type.getName() + "$" + name);
            return type.equals(candidate.getDeclaringClass()) ? candidate : null;
        } catch (ClassNotFoundException absent) {
            // Only a missing candidate is expected. Linkage failures are never absence.
            return null;
        }
    }

    private static List<Class<?>> directSupertypes(Class<?> type) {
        List<Class<?>> supertypes = new ArrayList<>();
        if (type.getSuperclass() != null) {
            supertypes.add(type.getSuperclass());
        }
        supertypes.addAll(Arrays.asList(type.getInterfaces()));
        return supertypes;
    }

    private static boolean inheritable(Class<?> member, Class<?> heir) {
        int modifiers = member.getModifiers();
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)
                || packageOf(member).equals(packageOf(heir));
    }

    private static String packageOf(Class<?> type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    /** Loads a class by binary name without initialising it. */
    interface Loader {
        Class<?> load(String binaryName) throws ClassNotFoundException;
    }
}
