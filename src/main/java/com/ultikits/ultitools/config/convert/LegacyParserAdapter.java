package com.ultikits.ultitools.config.convert;

import java.util.Map;
import com.ultikits.ultitools.config.document.PlainData;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.config.convert.builtin.PlainNormalizer;
import com.ultikits.ultitools.config.convert.ConverterRegistry.Context;

/** Frozen legacy parser semantics behind a detached input and explicit plain-output boundary. */
@ApiStatus.Internal
public final class LegacyParserAdapter implements ConfigConverter<Object> {
    // Stores the legacy implementation class, not a shared parser instance.
    @SuppressWarnings("removal")
    private final Class<? extends com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser> parserClass;

    /**
     * @param parserClass legacy parser's public no-argument implementation
     */
    // Accepts legacy implementations until their announced removal version.
    @SuppressWarnings("removal")
    public LegacyParserAdapter(Class<? extends com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser> parserClass) { this.parserClass = parserClass; }

    @Override
    // Invokes the frozen final legacy serialize contract on the original value.
    @SuppressWarnings({"unchecked", "removal"})
    public Object toPlain(Object value, ConversionContext context) throws ConversionException {
        Context ctx = (Context) context;
        return PlainNormalizer.normalize(((com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<Object>) parser(ctx)).serialize(value), ctx);
    }

    @Override
    // Invokes the frozen legacy parse contract on detached input.
    @SuppressWarnings("removal")
    public Object fromPlain(Object plain, ConversionContext context) throws ConversionException {
        Context ctx = (Context) context;
        Object detached = PlainData.copy(plain);
        return parser(ctx).parse(detached instanceof Map<?, ?> ? section((Map<?, ?>) detached) : detached);
    }

    // Creates a fresh public-noarg legacy parser for every conversion.
    @SuppressWarnings("removal")
    private com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser<?> parser(Context ctx) throws ConversionException {
        try { return parserClass.getConstructor().newInstance(); }
        catch (ReflectiveOperationException failure) {
            throw ctx.failure("Cannot construct legacy parser " + parserClass.getName(), failure);
        }
    }

    private static MemorySection section(Map<?, ?> plain) {
        MemoryConfiguration section = new MemoryConfiguration();
        for (Map.Entry<?, ?> entry : plain.entrySet()) {
            Object value = entry.getValue();
            section.set(String.valueOf(entry.getKey()), value instanceof Map<?, ?> ? section((Map<?, ?>) value) : value);
        }
        return section;
    }
}
