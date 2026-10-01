package com.ultikits.ultitools.config.convert;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.config.document.PlainData;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;
import com.ultikits.ultitools.utils.PackageScanUtils;

/**
 * Module-local converter lookup and pre-file type validation.
 * Prepared registries are published only after all packages and entities pass validation.
 * No cached value retains the module instance used as its weak key.
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class ConverterRegistry {
    private static final Map<UltiToolsPlugin, Prepared> MODULES = new WeakHashMap<>();
    private static final ConfigConverter<Object> PENDING = new ConfigConverter<Object>() {
        @Override
        public Object toPlain(Object value, ConversionContext ctx) throws ConversionException {
            throw new ConversionException("Built-in conversion is not installed yet", ctx.file(), ctx.path(), ctx.declaredType());
        }
        @Override
        public Object fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
            throw new ConversionException("Built-in conversion is not installed yet", ctx.file(), ctx.path(), ctx.declaredType());
        }
    };
    private static final ConfigConverter<Object> PLAIN_SCALAR = new ConfigConverter<Object>() {
        @Override
        public Object toPlain(Object value, ConversionContext ctx) { return value; }
        @Override
        public Object fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
            if (plain != null && !boxed(rawClass(ctx.declaredType())).isInstance(plain)) {
                throw new ConversionException("Value does not have the declared scalar type", ctx.file(), ctx.path(), ctx.declaredType());
            }
            return plain;
        }
    };
    private static final ConverterRegistry FRAMEWORK = createFramework();
    private final ConverterRegistry parent;
    private final Map<Class<?>, Registration> registrations = new LinkedHashMap<>();
    private boolean sealed;

    /** @param parent the parent registry, or null for an independent root */
    public ConverterRegistry(ConverterRegistry parent) { this.parent = parent; }

    /** @return the shared framework registry */
    public static ConverterRegistry framework() { return FRAMEWORK; }

    private static ConverterRegistry createFramework() {
        ConverterRegistry registry = new ConverterRegistry(null);
        for (Class<?> type : Arrays.asList(String.class, boolean.class, Boolean.class, int.class, Integer.class,
                long.class, Long.class, double.class, Double.class, BigInteger.class)) {
            registry.register(type, PLAIN_SCALAR, true);
        }
        for (Class<?> type : Arrays.asList(byte.class, Byte.class, short.class, Short.class, float.class,
                Float.class, char.class, Character.class, BigDecimal.class, UUID.class)) {
            registry.register(type, PENDING, true);
        }
        registry.sealed = true;
        return registry;
    }

    /**
     * Registers a converter without replacing an existing exact target registration.
     * @param type the target class
     * @param converter the converter
     * @param exact whether subclass lookup is forbidden
     */
    public void register(Class<?> type, ConfigConverter<?> converter, boolean exact) {
        if (sealed) { throw new IllegalStateException("Prepared converter registries are immutable"); }
        Registration previous = registrations.get(type);
        if (previous != null) {
            throw new ConfigurationException("Duplicate config converters for " + type.getName() + ": "
                    + previous.converter.getClass().getName() + " and " + converter.getClass().getName());
        }
        registrations.put(type, new Registration(converter, exact));
    }

    /**
     * Resolves local hierarchy first, then parent hierarchy, then generic and serializable hooks.
     * @param type the full declared type
     * @return the converter, or null if no supported conversion exists
     */
    public ConfigConverter<?> resolve(Type type) {
        Class<?> raw = rawClass(type);
        ConfigConverter<?> found = registered(raw);
        if (found != null) { return found; }
        if (raw == Object.class || raw.isArray() || type instanceof GenericArrayType || raw.isEnum()
                || raw == Enum.class || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
            return PENDING;
        }
        return ConfigurationSerializable.class.isAssignableFrom(raw) ? PENDING : null;
    }

    private ConfigConverter<?> registered(Class<?> raw) {
        Registration own = registrations.get(raw);
        if (own != null) { return own.converter; }
        for (Class<?> superclass = raw.getSuperclass(); superclass != null; superclass = superclass.getSuperclass()) {
            Registration inherited = registrations.get(superclass);
            if (inherited != null && !inherited.exact) { return inherited.converter; }
        }
        Deque<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> level = raw; level != null; level = level.getSuperclass()) {
            Collections.addAll(queue, level.getInterfaces());
        }
        Set<Class<?>> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Class<?> face = queue.removeFirst();
            if (!visited.add(face)) { continue; }
            Registration inherited = registrations.get(face);
            if (inherited != null && !inherited.exact) { return inherited.converter; }
            Collections.addAll(queue, face.getInterfaces());
        }
        return parent == null ? null : parent.registered(raw);
    }

    /**
     * Discovers all converters before checking any config entity or publishing the registry.
     * Repeating the same package set is idempotent; extending it checks the union atomically.
     * @param plugin the module used only as a weak cache key
     * @param packages the complete package set for this preparation
     * @param loader the module class loader
     * @return the prepared immutable registry
     */
    public static ConverterRegistry prepareModule(UltiToolsPlugin plugin, String[] packages, ClassLoader loader) {
        synchronized (MODULES) {
            Prepared previous = MODULES.get(plugin);
            Set<String> union = new LinkedHashSet<>();
            if (previous != null) { union.addAll(previous.packages); }
            Collections.addAll(union, packages);
            if (previous != null && previous.packages.containsAll(union)) { return previous.registry; }
            ConverterRegistry candidate = new ConverterRegistry(FRAMEWORK);
            Set<Class<?>> converterClasses = new LinkedHashSet<>();
            Set<Class<?>> entityClasses = new LinkedHashSet<>();
            for (String scanPackage : union) {
                converterClasses.addAll(PackageScanUtils.scanAnnotatedClasses(ConfigConverterFor.class, scanPackage, loader));
            }
            List<Class<?>> ordered = new ArrayList<>(converterClasses);
            ordered.sort(java.util.Comparator.comparing(Class::getName));
            for (Class<?> converterClass : ordered) { candidate.discover(converterClass); }
            for (String scanPackage : union) {
                entityClasses.addAll(PackageScanUtils.scanAnnotatedClasses(ConfigEntity.class, scanPackage, loader));
            }
            for (Class<?> entityClass : entityClasses) {
                candidate.checkEntityFields(entityClass, plugin.getPluginName(), entityClass.getAnnotation(ConfigEntity.class).value());
            }
            candidate.sealed = true;
            MODULES.put(plugin, new Prepared(candidate, union));
            return candidate;
        }
    }

    private void discover(Class<?> converterClass) {
        ConfigConverterFor annotation = converterClass.getAnnotation(ConfigConverterFor.class);
        if (!ConfigConverter.class.isAssignableFrom(converterClass)) {
            throw new ConfigurationException("Config converter " + converterClass.getName() + " must implement ConfigConverter");
        }
        try {
            ConfigConverter<?> converter = (ConfigConverter<?>) converterClass.getConstructor().newInstance();
            register(annotation.value(), converter, annotation.exact());
        } catch (ReflectiveOperationException e) {
            throw new ConfigurationException("Config converter " + converterClass.getName()
                    + " requires a public no-argument constructor", e);
        }
    }

    /** @param plugin the module @return whether preparation has succeeded for it */
    public static boolean hasModule(UltiToolsPlugin plugin) {
        synchronized (MODULES) { return MODULES.containsKey(plugin); }
    }

    /** @param plugin the module @return its prepared registry, or the framework registry */
    public static ConverterRegistry forModule(UltiToolsPlugin plugin) {
        synchronized (MODULES) {
            Prepared prepared = MODULES.get(plugin);
            return prepared == null ? FRAMEWORK : prepared.registry;
        }
    }

    /**
     * Checks every annotated field and every generic argument without reading or writing a file.
     * A nondefault legacy parser owns conversion and is adapted separately.
     * @param entity the config class, including inherited fields
     * @param module the module name
     * @param file the module-relative config file
     */
    public void checkEntityFields(Class<?> entity, String module, String file) {
        for (Class<?> level = entity; level != null && level != Object.class; level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                ConfigEntry entry = field.getAnnotation(ConfigEntry.class);
                if (entry == null || entry.parser() != DefaultConfigParser.class) { continue; }
                Type missing = missingType(field.getGenericType(), new HashSet<>());
                if (missing == null) { continue; }
                Class<?> missingClass = rawClass(missing);
                String key = entry.path().isEmpty() ? field.getName() : entry.path();
                throw new ConfigurationException("Module " + module + ", file " + file + ", key \"" + key
                        + "\": no config converter for " + missingClass.getName() + " (declared as "
                        + describe(field.getGenericType()) + "). Register one with @ConfigConverterFor("
                        + missingClass.getSimpleName() + ".class) in the module's scan packages, or declare the value type as Map<String, Object>.");
            }
        }
    }

    private Type missingType(Type type, Set<Type> visiting) {
        if (!visiting.add(type)) { return null; }
        try {
            if (type instanceof TypeVariable<?>) {
                for (Type bound : ((TypeVariable<?>) type).getBounds()) {
                    Type missing = missingType(bound, visiting);
                    if (missing != null) { return missing; }
                }
            } else if (type instanceof WildcardType) {
                for (Type bound : ((WildcardType) type).getUpperBounds()) {
                    Type missing = missingType(bound, visiting);
                    if (missing != null) { return missing; }
                }
                for (Type bound : ((WildcardType) type).getLowerBounds()) {
                    Type missing = missingType(bound, visiting);
                    if (missing != null) { return missing; }
                }
            } else {
                if (resolve(type) == null) { return type; }
                if (type instanceof ParameterizedType) {
                    for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                        Type missing = missingType(argument, visiting);
                        if (missing != null) { return missing; }
                    }
                } else if (type instanceof GenericArrayType) {
                    return missingType(((GenericArrayType) type).getGenericComponentType(), visiting);
                } else if (type instanceof Class<?> && ((Class<?>) type).isArray()) {
                    return missingType(((Class<?>) type).getComponentType(), visiting);
                }
            }
            return null;
        } finally { visiting.remove(type); }
    }

    private static String describe(Type type) {
        if (type instanceof Class<?>) { return ((Class<?>) type).getSimpleName(); }
        if (type instanceof ParameterizedType) {
            ParameterizedType generic = (ParameterizedType) type;
            List<String> arguments = new ArrayList<>();
            for (Type argument : generic.getActualTypeArguments()) { arguments.add(describe(argument)); }
            return describe(generic.getRawType()) + "<" + String.join(", ", arguments) + ">";
        }
        return type.getTypeName();
    }

    static Class<?> rawClass(Type type) {
        if (type instanceof Class<?>) { return (Class<?>) type; }
        if (type instanceof ParameterizedType) { return rawClass(((ParameterizedType) type).getRawType()); }
        if (type instanceof GenericArrayType) { return Object[].class; }
        if (type instanceof TypeVariable<?>) { return rawClass(((TypeVariable<?>) type).getBounds()[0]); }
        if (type instanceof WildcardType) { return rawClass(((WildcardType) type).getUpperBounds()[0]); }
        throw new IllegalArgumentException("Unsupported Java type: " + type);
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) { return Boolean.class; }
        if (type == int.class) { return Integer.class; }
        if (type == long.class) { return Long.class; }
        if (type == double.class) { return Double.class; }
        return type;
    }

    /**
     * Converts a Java value and enforces the storage plain-data boundary even for custom output.
     * @param value the Java value
     * @param type its declared type
     * @param file the module-relative file
     * @param path the whole keys
     * @return plain data
     * @throws ConversionException when conversion or plain-data validation fails
     */
    public Object toPlain(Object value, Type type, String file, List<String> path) throws ConversionException {
        return write(value, new Context(this, file, path, type, new ArrayList<>()));
    }

    /**
     * Converts a document value through this registry.
     * @param plain the document value
     * @param type the declared Java type
     * @param file the module-relative file
     * @param path the whole keys
     * @param <T> the result type
     * @return the converted value
     * @throws ConversionException on a root conversion failure
     */
    public <T> T fromPlain(Object plain, Type type, String file, List<String> path) throws ConversionException {
        return this.<T>fromPlainResult(plain, type, file, path).value();
    }

    /**
     * Converts with an internal diagnostic collector shared by every nested context.
     * @param plain the document value
     * @param type the declared type
     * @param file the module-relative file
     * @param path the whole keys
     * @param <T> the result type
     * @return the value and all collected nested failures
     * @throws ConversionException on a scalar-root failure
     */
    public <T> ConversionResult<T> fromPlainResult(Object plain, Type type, String file, List<String> path)
            throws ConversionException {
        List<ConversionFailure> failures = new ArrayList<>();
        T value = read(plain, new Context(this, file, path, type, failures));
        return new ConversionResult<>(value, failures);
    }

    @SuppressWarnings("unchecked")
    private Object write(Object value, Context ctx) throws ConversionException {
        if (value == null) { return null; }
        ConfigConverter<Object> converter = (ConfigConverter<Object>) resolve(ctx.declaredType());
        if (converter == null) { throw ctx.failure("No config converter for " + ctx.declaredType().getTypeName(), null); }
        try {
            Object result = converter.toPlain(value, ctx);
            PlainData.requirePlain(ctx.path(), result);
            return result;
        } catch (RuntimeException e) { throw ctx.failure(e.getMessage(), e); }
    }

    @SuppressWarnings("unchecked")
    private <T> T read(Object plain, Context ctx) throws ConversionException {
        if (plain == null) { return null; }
        ConfigConverter<Object> converter = (ConfigConverter<Object>) resolve(ctx.declaredType());
        if (converter == null) { throw ctx.failure("No config converter for " + ctx.declaredType().getTypeName(), null); }
        try { return (T) converter.fromPlain(plain, ctx); }
        catch (RuntimeException e) { throw ctx.failure(e.getMessage(), e); }
    }

    private static final class Registration {
        private final ConfigConverter<?> converter;
        private final boolean exact;
        private Registration(ConfigConverter<?> converter, boolean exact) {
            this.converter = converter;
            this.exact = exact;
        }
    }

    private static final class Prepared {
        private final ConverterRegistry registry;
        private final Set<String> packages;
        private Prepared(ConverterRegistry registry, Set<String> packages) {
            this.registry = registry;
            this.packages = Collections.unmodifiableSet(new LinkedHashSet<>(packages));
        }
    }

    static final class Context implements ConversionContext {
        private final ConverterRegistry registry;
        private final String file;
        private final List<String> path;
        private final Type type;
        private final List<ConversionFailure> failures;
        private Context(ConverterRegistry registry, String file, List<String> path, Type type,
                        List<ConversionFailure> failures) {
            this.registry = registry;
            this.file = file;
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.type = type;
            this.failures = failures;
        }
        @Override public String file() { return file; }
        @Override public List<String> path() { return path; }
        @Override public Type declaredType() { return type; }
        @Override public Object toPlain(Object nested) throws ConversionException {
            return registry.write(nested, new Context(registry, file, path,
                    nested == null ? Object.class : nested.getClass(), failures));
        }
        @Override public <V> V fromPlain(Object nested, Type declared) throws ConversionException {
            return registry.read(nested, new Context(registry, file, path, declared, failures));
        }
        Context child(String segment, Type declared) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(segment);
            return new Context(registry, file, childPath, declared, failures);
        }
        void record(Object raw, ConversionException cause) { failures.add(new ConversionFailure(raw, cause)); }
        ConversionException failure(String reason, Throwable cause) {
            return new ConversionException(reason, file, path, type, cause);
        }
    }
}
