package com.ultikits.ultitools.config.convert;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Checked conversion failure carrying the file, whole key path, and declared Java type.
 *
 * @since 6.3.0
 */
public class ConversionException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String file;
    private final List<String> path;
    private final Type declaredType;

    /**
     * Creates a conversion failure at the given location.
     * @param message the reason
     * @param file the module-relative file
     * @param path the whole key segments
     * @param declaredType the declared Java type
     */
    public ConversionException(String message, String file, List<String> path, Type declaredType) {
        this(message, file, path, declaredType, null);
    }

    /**
     * Creates a conversion failure with its underlying cause.
     * @param message the reason
     * @param file the module-relative file
     * @param path the whole key segments
     * @param declaredType the declared Java type
     * @param cause the underlying failure
     */
    public ConversionException(String message, String file, List<String> path, Type declaredType, Throwable cause) {
        super("File " + file + ", key " + path + ", declared as " + declaredType.getTypeName() + ": " + message, cause);
        this.file = file;
        this.path = Collections.unmodifiableList(new ArrayList<>(path));
        this.declaredType = declaredType;
    }

    /** @return the configuration file */
    public String file() { return file; }
    /** @return the immutable whole key path */
    public List<String> path() { return path; }
    /** @return the declared Java type */
    public Type declaredType() { return declaredType; }
}
