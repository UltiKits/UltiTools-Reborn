package com.ultikits.ultitools.config.convert.builtin;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConversionException;
import com.ultikits.ultitools.config.convert.ConverterRegistry.Context;
import com.ultikits.ultitools.config.document.PlainData;

/** Generic factory categories, their construction policy, and per-element diagnostic boundaries. */
@ApiStatus.Internal
public final class GenericConverters implements ConfigConverter<Object> {
    private static final Set<Class<?>> CATALOG = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            Object.class, Enum.class, Object[].class, Collection.class, List.class, ArrayList.class,
            Set.class, LinkedHashSet.class, SortedSet.class, TreeSet.class, EnumSet.class,
            Queue.class, Deque.class, ArrayDeque.class, Map.class, LinkedHashMap.class,
            ConcurrentMap.class, ConcurrentHashMap.class, EnumMap.class, SortedMap.class, TreeMap.class)));
    private static final GenericConverters INSTANCE = new GenericConverters();
    private GenericConverters() { }

    /** @return the implementation-owned supported builtin factory categories */
    public static Set<Class<?>> registeredTypes() { return CATALOG; }

    /** @param type the declared type @return a generic converter only when its result can be constructed */
    public static ConfigConverter<?> resolve(Type type) {
        Class<?> raw = ConversionTypes.raw(type);
        if (raw == Object.class || Enum.class.isAssignableFrom(raw) || raw.isArray()) { return INSTANCE; }
        if (!Collection.class.isAssignableFrom(raw) && !Map.class.isAssignableFrom(raw)) { return null; }
        if (CATALOG.contains(raw)) { return INSTANCE; }
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers()) || !Modifier.isPublic(raw.getModifiers())) { return null; }
        try { raw.getConstructor(); return INSTANCE; }
        catch (NoSuchMethodException e) { return null; }
    }

    /** @param type runtime/declaration type @return constructor-free write converter */
    public static ConfigConverter<?> resolveWrite(Type type) {
        Class<?> raw = ConversionTypes.raw(type);
        return raw == Object.class || Enum.class.isAssignableFrom(raw) || raw.isArray()
                || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw) ? INSTANCE : null;
    }

    @Override
    public Object toPlain(Object value, ConversionContext context) throws ConversionException {
        Context ctx = (Context) context;
        Class<?> raw = ConversionTypes.raw(ctx.declaredType());
        if (raw == Object.class) {
            if (PlainData.isPlain(value)) { return PlainData.copy(value); }
            if (value.getClass() == Object.class) { throw ctx.failure("No converter for runtime java.lang.Object", null); }
            return ctx.writeTyped(value, value.getClass());
        }
        if (Enum.class.isAssignableFrom(raw)) { return ((Enum<?>) value).name(); }
        if (raw.isArray()) {
            Type component = ConversionTypes.component(ctx.declaredType());
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(ctx.child(Integer.toString(index), component).writeTyped(Array.get(value, index), component));
            }
            return values;
        }
        if (value instanceof Collection<?>) {
            Type element = ConversionTypes.argument(ctx.declaredType(), Collection.class, 0);
            List<Object> values = new ArrayList<>();
            int index = 0;
            for (Object nested : (Collection<?>) value) {
                values.add(ctx.child(Integer.toString(index++), element).writeTyped(nested, element));
            }
            return values;
        }
        if (value instanceof Map<?, ?>) {
            Type keyType = ConversionTypes.argument(ctx.declaredType(), Map.class, 0);
            Type valueType = ConversionTypes.argument(ctx.declaredType(), Map.class, 1);
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Context keyContext = ctx.child(String.valueOf(entry.getKey()), keyType);
                Object key = keyContext.writeTyped(entry.getKey(), keyType);
                if (!(key instanceof String)) { throw keyContext.failure("Map keys must convert to text", null); }
                String text = (String) key;
                if (values.containsKey(text)) { throw ctx.child(text, keyType).failure("Map keys collide after conversion", null); }
                values.put(text, ctx.child(text, valueType).writeTyped(entry.getValue(), valueType));
            }
            return values;
        }
        throw ctx.failure("Value does not match the declared container shape", null);
    }

    @Override
    public Object fromPlain(Object plain, ConversionContext context) throws ConversionException {
        Context ctx = (Context) context;
        Class<?> raw = ConversionTypes.raw(ctx.declaredType());
        if (raw == Object.class) { PlainData.requirePlain(ctx.path(), plain); return PlainData.copy(plain); }
        if (Enum.class.isAssignableFrom(raw)) { return readEnum(plain, raw, ctx); }
        if (raw.isArray()) {
            if (!(plain instanceof List<?>)) { throw ctx.failure("Expected a list for an array", null); }
            Type component = ConversionTypes.component(ctx.declaredType());
            List<Object> valid = new ArrayList<>();
            readElements((List<?>) plain, component, ctx, valid);
            Object array = Array.newInstance(ConversionTypes.raw(component), valid.size());
            for (int index = 0; index < valid.size(); index++) { Array.set(array, index, valid.get(index)); }
            return array;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            if (!(plain instanceof List<?>)) { throw ctx.failure("Expected a list for a collection", null); }
            Type element = ConversionTypes.argument(ctx.declaredType(), Collection.class, 0);
            Collection<Object> values = newCollection(raw, element, ctx);
            readElements((List<?>) plain, element, ctx, values);
            return values;
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (!(plain instanceof Map<?, ?>)) { throw ctx.failure("Expected a map", null); }
            Type keyType = ConversionTypes.argument(ctx.declaredType(), Map.class, 0);
            Type valueType = ConversionTypes.argument(ctx.declaredType(), Map.class, 1);
            Map<Object, Object> values = newMap(raw, keyType, ctx);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) plain).entrySet()) {
                String segment = String.valueOf(entry.getKey());
                Context keyContext = ctx.child(segment, keyType);
                Context valueContext = ctx.child(segment, valueType);
                Object key;
                try {
                    key = keyContext.readTyped(entry.getKey(), keyType);
                    if (values.containsKey(key)) {
                        throw keyContext.failure("Map keys collide after conversion", null);
                    }
                } catch (ConversionException e) {
                    keyContext.record(entry.getKey(), e);
                    continue;
                } catch (RuntimeException e) {
                    keyContext.record(entry.getKey(), keyContext.failure("Map key cannot be inserted", e));
                    continue;
                }
                try {
                    Object value = valueContext.readTyped(entry.getValue(), valueType);
                    values.put(key, value);
                } catch (ConversionException e) { valueContext.record(entry.getValue(), e); }
                catch (RuntimeException e) { valueContext.record(entry.getValue(), valueContext.failure("Map entry cannot be inserted", e)); }
            }
            return values;
        }
        throw ctx.failure("Unsupported container", null);
    }

    private static void readElements(List<?> plain, Type element, Context ctx, Collection<Object> values) {
        for (int index = 0; index < plain.size(); index++) {
            Object nested = plain.get(index);
            Context child = ctx.child(Integer.toString(index), element);
            try {
                if (nested == null && ConversionTypes.raw(element) != Object.class) {
                    throw child.failure("Null is not a typed collection element", null);
                }
                Object converted = child.readTyped(nested, element);
                values.add(converted);
            } catch (ConversionException e) { child.record(nested, e); }
            catch (RuntimeException e) { child.record(nested, child.failure("Collection element cannot be inserted", e)); }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readEnum(Object plain, Class<?> raw, Context ctx) throws ConversionException {
        if (!(plain instanceof String) || !raw.isEnum()) { throw ctx.failure("Expected a named concrete enum", null); }
        try { return Enum.valueOf((Class) raw, (String) plain); }
        catch (IllegalArgumentException e) { throw ctx.failure("Unknown enum name " + plain, e); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Collection<Object> newCollection(Class<?> raw, Type element, Context ctx) throws ConversionException {
        if (raw == Collection.class || raw == List.class || raw == ArrayList.class) { return new ArrayList<>(); }
        if (raw == Set.class || raw == LinkedHashSet.class) { return new LinkedHashSet<>(); }
        if (raw == SortedSet.class || raw == TreeSet.class) { return new TreeSet<>(); }
        if (raw == Queue.class || raw == Deque.class || raw == ArrayDeque.class) { return new ArrayDeque<>(); }
        if (raw == EnumSet.class) {
            Class<?> enumClass = ConversionTypes.raw(element);
            if (!enumClass.isEnum()) { throw ctx.failure("EnumSet requires a concrete enum type", null); }
            return (Collection) EnumSet.noneOf((Class) enumClass);
        }
        return (Collection<Object>) construct(raw, ctx);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Object, Object> newMap(Class<?> raw, Type key, Context ctx) throws ConversionException {
        if (raw == Map.class || raw == LinkedHashMap.class) { return new LinkedHashMap<>(); }
        if (raw == ConcurrentMap.class || raw == ConcurrentHashMap.class) { return new ConcurrentHashMap<>(); }
        if (raw == SortedMap.class || raw == TreeMap.class) { return new TreeMap<>(); }
        if (raw == EnumMap.class) {
            Class<?> enumClass = ConversionTypes.raw(key);
            if (!enumClass.isEnum()) { throw ctx.failure("EnumMap requires a concrete enum key type", null); }
            return new EnumMap((Class) enumClass);
        }
        return (Map<Object, Object>) construct(raw, ctx);
    }

    private static Object construct(Class<?> raw, Context ctx) throws ConversionException {
        try { return raw.getConstructor().newInstance(); }
        catch (ReflectiveOperationException e) { throw ctx.failure("Cannot construct container " + raw.getName(), e); }
    }
}
