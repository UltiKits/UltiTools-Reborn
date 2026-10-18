package com.ultikits.ultitools.buildtools.deprecation;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads {@code java.lang.Deprecated} and its {@code since}/{@code forRemoval} values straight from a
 * compiled class file's {@code RuntimeVisibleAnnotations} attributes.
 * <p>
 * Reflective annotation access ({@code isAnnotationPresent}, {@code getAnnotation}) parses every
 * runtime annotation on the element and resolves each value, which initializes any enum type a
 * co-located annotation names. Reading the bytes only defines, links and initializes nothing.
 * Compile-time constant expressions ({@code since = VERSION}, {@code forRemoval = (true)}) are
 * already folded by javac into the constant pool, so no source expression is ever evaluated here.
 */
final class CompiledDeprecationReader {
    private static final String DEPRECATED = "Ljava/lang/Deprecated;";
    private static final String CLASS_KEY = "";

    /** Deprecated metadata of one compiled declaration. */
    static final class Metadata {
        final String since;
        final boolean forRemoval;

        Metadata(String since, boolean forRemoval) {
            this.since = since;
            this.forRemoval = forRemoval;
        }
    }

    private static final Metadata NOT_DEPRECATED = new Metadata(null, false);
    private static final Map<Class<?>, String> PRIMITIVES = new HashMap<>();

    static {
        PRIMITIVES.put(boolean.class, "Z");
        PRIMITIVES.put(byte.class, "B");
        PRIMITIVES.put(char.class, "C");
        PRIMITIVES.put(short.class, "S");
        PRIMITIVES.put(int.class, "I");
        PRIMITIVES.put(long.class, "J");
        PRIMITIVES.put(float.class, "F");
        PRIMITIVES.put(double.class, "D");
        PRIMITIVES.put(void.class, "V");
    }

    private final String className;
    private final Map<String, Metadata> declarations = new HashMap<>();

    private CompiledDeprecationReader(String className) {
        this.className = className;
    }

    /** Parses the class file the given loader serves for {@code type}; nothing is loaded or run. */
    static CompiledDeprecationReader read(ClassLoader loader, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream stream = loader.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Compiled class file not found: " + resource);
        }
        CompiledDeprecationReader reader = new CompiledDeprecationReader(type.getName());
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            reader.parse(in);
        }
        return reader;
    }

    /** Returns the class-level metadata, or {@code null} when the class is not annotated. */
    Metadata forClass() {
        return present(declarations.get(CLASS_KEY));
    }

    /** Returns the field's metadata, or {@code null} when it is not annotated. */
    Metadata forField(Field field) {
        return member(field.getName(), descriptor(field.getType()));
    }

    /** Returns the method's or constructor's metadata, or {@code null} when it is not annotated. */
    Metadata forExecutable(Executable executable) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : executable.getParameterTypes()) {
            descriptor.append(descriptor(parameter));
        }
        descriptor.append(')');
        if (executable instanceof Constructor) {
            return member("<init>", descriptor.append('V').toString());
        }
        return member(executable.getName(), descriptor.append(descriptor(((Method) executable).getReturnType()))
                .toString());
    }

    private Metadata member(String name, String descriptor) {
        String key = name + ":" + descriptor;
        if (!declarations.containsKey(key)) {
            throw new IllegalStateException("Class file of " + className + " has no member " + key);
        }
        return present(declarations.get(key));
    }

    private static Metadata present(Metadata metadata) {
        return metadata == NOT_DEPRECATED ? null : metadata;
    }

    private void parse(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) {
            throw new IllegalStateException("Not a class file: " + className);
        }
        skip(in, 4);
        Object[] pool = readConstantPool(in);
        skip(in, 2);
        String thisClass = utf8(pool, (Integer) pool[in.readUnsignedShort()]);
        if (!thisClass.equals(className.replace('.', '/'))) {
            throw new IllegalStateException("Class file names " + thisClass + ", expected " + className);
        }
        skip(in, 2);
        skip(in, 2 * in.readUnsignedShort());
        readMembers(in, pool);
        readMembers(in, pool);
        declarations.put(CLASS_KEY, readAttributes(in, pool));
    }

    private void readMembers(DataInputStream in, Object[] pool) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            skip(in, 2);
            String key = utf8(pool, in.readUnsignedShort()) + ":" + utf8(pool, in.readUnsignedShort());
            declarations.put(key, readAttributes(in, pool));
        }
    }

    private static Object[] readConstantPool(DataInputStream in) throws IOException {
        Object[] pool = new Object[in.readUnsignedShort()];
        for (int i = 1; i < pool.length; i++) {
            int tag = in.readUnsignedByte();
            if (tag == 1) {
                pool[i] = in.readUTF();
            } else if (tag == 3) {
                pool[i] = new int[] {in.readInt()};
            } else if (tag == 7) {
                pool[i] = in.readUnsignedShort();
            } else if (tag == 5 || tag == 6) {
                skip(in, 8);
                i++;
            } else {
                skip(in, constantSize(tag));
            }
        }
        return pool;
    }

    private static int constantSize(int tag) {
        switch (tag) {
            case 8: case 16: case 19: case 20:
                return 2;
            case 15:
                return 3;
            case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                return 4;
            default:
                throw new IllegalStateException("Unknown constant pool tag " + tag);
        }
    }

    private static Metadata readAttributes(DataInputStream in, Object[] pool) throws IOException {
        Metadata result = NOT_DEPRECATED;
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String name = utf8(pool, in.readUnsignedShort());
            int length = in.readInt();
            if ("RuntimeVisibleAnnotations".equals(name)) {
                result = readAnnotations(in, pool);
            } else {
                skip(in, length);
            }
        }
        return result;
    }

    private static Metadata readAnnotations(DataInputStream in, Object[] pool) throws IOException {
        Metadata result = NOT_DEPRECATED;
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            if (DEPRECATED.equals(utf8(pool, in.readUnsignedShort()))) {
                result = readDeprecated(in, pool);
            } else {
                skipPairs(in);
            }
        }
        return result;
    }

    private static Metadata readDeprecated(DataInputStream in, Object[] pool) throws IOException {
        String since = null;
        boolean forRemoval = false;
        int pairs = in.readUnsignedShort();
        for (int i = 0; i < pairs; i++) {
            String element = utf8(pool, in.readUnsignedShort());
            int tag = in.readUnsignedByte();
            int index = in.readUnsignedShort();
            if ("since".equals(element) && tag == 's') {
                since = utf8(pool, index);
            } else if ("forRemoval".equals(element) && tag == 'Z') {
                forRemoval = ((int[]) pool[index])[0] != 0;
            } else {
                throw new IllegalStateException("Unsupported Deprecated element " + element + " tag " + (char) tag);
            }
        }
        return new Metadata(since, forRemoval);
    }

    private static void skipPairs(DataInputStream in) throws IOException {
        int pairs = in.readUnsignedShort();
        for (int i = 0; i < pairs; i++) {
            skip(in, 2);
            skipElementValue(in);
        }
    }

    private static void skipElementValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        if (tag == 'e') {
            skip(in, 4);
        } else if (tag == '@') {
            skip(in, 2);
            skipPairs(in);
        } else if (tag == '[') {
            int values = in.readUnsignedShort();
            for (int i = 0; i < values; i++) {
                skipElementValue(in);
            }
        } else if ("BCDFIJSZsc".indexOf(tag) >= 0) {
            skip(in, 2);
        } else {
            throw new IllegalStateException("Unknown annotation element tag " + tag);
        }
    }

    private static String utf8(Object[] pool, int index) {
        Object value = pool[index];
        if (!(value instanceof String)) {
            throw new IllegalStateException("Constant pool entry " + index + " is not UTF-8");
        }
        return (String) value;
    }

    private static void skip(DataInputStream in, int bytes) throws IOException {
        if (in.skipBytes(bytes) != bytes) {
            throw new EOFException("Truncated class file");
        }
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (type.isPrimitive()) {
            return PRIMITIVES.get(type);
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
