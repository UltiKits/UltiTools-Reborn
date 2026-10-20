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
import com.ultikits.ultitools.config.convert.ConverterRegistry;
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

    /**

     * @param type the declared type

     * @return a generic converter only when its result can be constructed

     */
    public static ConfigConverter<?> resolve(Type type) {
        Class<?> raw = ConversionTypes.raw(type);
        if (raw == Object.class || Enum.class.isAssignableFrom(raw) || raw.isArray()) { return INSTANCE; }
        if (!Collection.class.isAssignableFrom(raw) && !Map.class.isAssignableFrom(raw)) { return null; }
        if (CATALOG.contains(raw)) { return INSTANCE; }
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers()) || !Modifier.isPublic(raw.getModifiers())) { return null; }
        try { raw.getConstructor(); return INSTANCE; }
        catch (NoSuchMethodException e) { return null; }
    }

    /**

     * @param type runtime/declaration type

     * @return constructor-free write converter

     */
    public static ConfigConverter<?> resolveWrite(Type type) {
        Class<?> raw = ConversionTypes.raw(type);
        return raw == Object.class || Enum.class.isAssignableFrom(raw) || raw.isArray()
                || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw) ? INSTANCE : null;
    }

    @Override
    @SuppressWarnings("PMD.NPathComplexity") // Dispatch enum, array, collection and map output with declared nested types.
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
                Object nested = Array.get(value, index);
                Context child = ctx.child(Integer.toString(index), component);
                if (nested == null) { child.record(null, child.failure("Null reference-array element omitted", null)); }
                else { values.add(child.writeTyped(nested, component)); }
            }
            return values;
        }
        if (value instanceof Collection<?>) {
            Type element = ConversionTypes.argument(ctx.declaredType(), Collection.class, 0);
            List<Object> values = new ArrayList<>();
            int index = 0;
            for (Object nested : (Collection<?>) value) {
                Context child = ctx.child(Integer.toString(index++), element);
                if (nested == null) { child.record(null, child.failure("Null typed collection element omitted", null)); }
                else { values.add(child.writeTyped(nested, element)); }
            }
            return values;
        }
        if (value instanceof Map<?, ?>) { return writeMap((Map<?, ?>) value, ctx); }
        throw ctx.failure("Value does not match the declared container shape", null);
    }

    /**
     * Writes each entry under the file's own key text for its key when the file value at this location holds one (the
     * first in file order), else under the key's verified text, and descends into the value under that text so nested
     * maps bind too.
     */
    private static Map<String, Object> writeMap(Map<?, ?> value, Context ctx) throws ConversionException {
        Type keyType = ConversionTypes.argument(ctx.declaredType(), Map.class, 0);
        Type valueType = ConversionTypes.argument(ctx.declaredType(), Map.class, 1);
        Map<String, Object> values = new LinkedHashMap<>();
        Map<Object, List<String>> spellings = fileKeyIndex(ctx, keyType);
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Context keyContext = ctx.child(String.valueOf(entry.getKey()), keyType);
            Object key = keyContext.writeTyped(entry.getKey(), keyType);
            List<String> fileSpellings = key == null ? null : spellings.get(entry.getKey());
            String text = fileSpellings == null
                    ? keyText(key, entry.getKey(), keyType, keyContext) : fileSpellings.get(0);
            if (values.containsKey(text)) { throw ctx.child(text, keyType).failure("Map keys collide after conversion", null); }
            values.put(text, ctx.child(text, valueType).writeTyped(entry.getValue(), valueType));
        }
        return values;
    }

    @Override
    @SuppressWarnings("PMD.NPathComplexity") // Dispatch declared shapes and exact scalar policies with located failure boundaries.
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

    /**
     * Whether a map with this declared key type binds its keys to the file's key text: every key type other than
     * {@code String} and {@code Object}, whose keys are already the text the file holds.
     *
     * @param keyType declared map key type
     * @return whether the key is typed
     */
    @ApiStatus.Internal
    public static boolean bindsKeyText(Type keyType) {
        Class<?> raw = ConversionTypes.raw(keyType);
        return raw != String.class && raw != Object.class;
    }

    /**
     * Indexes the keys of the map the file holds at {@code ctx}'s location by the typed key each reads back to, through
     * {@code keyType} and the same registry read path the loader uses. Each typed key maps to its file key texts in file
     * order; a file key that does not read as the key type is skipped. Reading a key is a probe, so nothing is recorded
     * in the conversion's failures. The one index behind both the map writer's binding (first spelling wins) and the
     * file-key ambiguity scan (two or more spellings).
     *
     * @param ctx context whose file value is the map to index
     * @param keyType declared map key type
     * @return typed key to its file spellings in file order; empty when the key type is not typed or the file value at
     *         this location is not a map
     */
    @ApiStatus.Internal
    public static Map<Object, List<String>> fileKeyIndex(Context ctx, Type keyType) {
        Map<Object, List<String>> index = new LinkedHashMap<>();
        if (!bindsKeyText(keyType) || !(ctx.fileValue() instanceof Map<?, ?>)) { return index; }
        for (Object fileKey : ((Map<?, ?>) ctx.fileValue()).keySet()) {
            if (fileKey == null) { continue; }
            String text = String.valueOf(fileKey);
            ConverterRegistry.Probe read = ctx.child(text, keyType).probe(fileKey, keyType);
            if (read.succeeded() && read.value() != null) {
                List<String> spellings = index.get(read.value());
                if (spellings == null) { spellings = new ArrayList<>(); index.put(read.value(), spellings); }
                spellings.add(text);
            }
        }
        return index;
    }

    /**
     * The text a converted map key is written under. A key that converts to text keeps that text. A key that converts
     * to another plain scalar ({@code Integer}, {@code Long}, {@code Boolean}, ...) is written as that scalar's text only
     * when the text reads back, through the declared key type and the same registry path the loader uses, to a key
     * equal to the entry's own; anything else is refused, so a key is never written under a text that loads as a
     * different key. The read-back is a probe and records nothing in the conversion's failures.
     */
    private static String keyText(Object plain, Object javaKey, Type keyType, Context keyContext) throws ConversionException {
        if (plain instanceof String) { return (String) plain; }
        if (plain != null && !(plain instanceof Map<?, ?>) && !(plain instanceof Collection<?>)) {
            String text = String.valueOf(plain);
            ConverterRegistry.Probe back = keyContext.probe(text, keyType);
            if (back.succeeded() && javaKey.equals(back.value())) { return text; }
        }
        throw keyContext.failure("Map keys must convert to text", null);
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

    @SuppressWarnings({"unchecked", "rawtypes", "PMD.NPathComplexity"})
    // Each supported collection category has a distinct construction and insertion policy.
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
