package com.ultikits.ultitools.text;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;

/**
 * A stable message key of the form {@code namespace:path}, such as
 * {@code ultiessentials:home.set.success}. The namespace identifies a module's
 * catalogue and is derived from its {@code plugin.yml} name with {@link #namespaceFor(String)}.
 * The path consists of lowercase, dot-separated {@code [a-z0-9_-]} segments.
 *
 * @param namespace the catalogue namespace
 * @param path the catalogue entry path
 */
public record MessageKey(String namespace, String path) {
    /** The framework's own namespace. */
    public static final String FRAMEWORK_NAMESPACE = "ultitools";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)*");

    /**
     * Validates both parts, each at most 128 characters.
     *
     * @throws IllegalArgumentException if a part is invalid or the namespace is {@code ..}
     */
    public MessageKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.length() > 128 || "..".equals(namespace) || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid message namespace: " + namespace);
        }
        if (path.length() > 128 || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid message path: " + path);
        }
    }

    /** Returns a validated key, equivalent to the canonical constructor. */
    public static MessageKey of(String namespace, String path) {
        return new MessageKey(namespace, path);
    }

    /**
     * Parses {@code namespace:path}.
     *
     * @throws IllegalArgumentException unless exactly one colon separates two valid parts
     */
    public static MessageKey parse(String namespacedKey) {
        Objects.requireNonNull(namespacedKey, "namespacedKey");
        int colon = namespacedKey.indexOf(':');
        if (colon < 0 || colon != namespacedKey.lastIndexOf(':')) {
            throw new IllegalArgumentException("Expected exactly one ':' in message key: " + namespacedKey);
        }
        return of(namespacedKey.substring(0, colon), namespacedKey.substring(colon + 1));
    }

    /**
     * Lowercases a module name with {@link Locale#ROOT}, replacing every character
     * outside {@code [a-z0-9_.-]} with {@code _}. Uniqueness is checked at module load.
     *
     * @throws IllegalArgumentException if the result is empty, {@code ..}, or longer than 128 characters
     */
    public static String namespaceFor(String moduleName) {
        Objects.requireNonNull(moduleName, "moduleName");
        String namespace = moduleName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        if (namespace.isEmpty() || "..".equals(namespace) || namespace.length() > 128) {
            throw new IllegalArgumentException("Invalid module namespace: " + moduleName);
        }
        return namespace;
    }

    /** Returns {@code namespace + ":" + path}, the Adventure translation key. */
    public String translationKey() {
        return namespace + ':' + path;
    }

    /** Returns this key as an Adventure key; always valid by construction. */
    public Key asKey() {
        return Key.key(namespace, path);
    }

    /** Returns {@link #translationKey()}. */
    @Override
    public String toString() {
        return translationKey();
    }
}
