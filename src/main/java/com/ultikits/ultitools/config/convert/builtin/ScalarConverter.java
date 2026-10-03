package com.ultikits.ultitools.config.convert.builtin;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConversionException;

/** Exact scalar conversions; numeric decisions never narrow through a double cast. */
@ApiStatus.Internal
public final class ScalarConverter implements ConfigConverter<Object> {
    private final Class<?> target;

    /** @param target the scalar target, primitive or wrapper */
    public ScalarConverter(Class<?> target) { this.target = ConversionTypes.boxed(target); }

    @Override
    public Object toPlain(Object value, ConversionContext ctx) throws ConversionException {
        if (!target.isInstance(value)) { throw failure(ctx, "Value is not a " + target.getName()); }
        if (target == Byte.class || target == Short.class) { return ((Number) value).intValue(); }
        if (target == Float.class) { return Double.valueOf(Float.toString((Float) value)); }
        if (target == Character.class || target == BigDecimal.class || target == UUID.class) { return value.toString(); }
        return value;
    }

    @Override
    @SuppressWarnings("PMD.NPathComplexity") // Dispatch declared shapes and exact scalar policies with located failure boundaries.
    public Object fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
        if (target == String.class) {
            if (plain instanceof String || plain instanceof Number || plain instanceof Boolean || plain instanceof Character) {
                return String.valueOf(plain);
            }
            throw failure(ctx, "Expected scalar text");
        }
        if (target == Boolean.class) {
            if (plain instanceof Boolean) { return plain; }
            if ("true".equals(plain)) { return true; }
            if ("false".equals(plain)) { return false; }
            throw failure(ctx, "Expected true or false");
        }
        if (target == Character.class) {
            if (plain instanceof String && ((String) plain).length() == 1) { return ((String) plain).charAt(0); }
            throw failure(ctx, "Expected exactly one character");
        }
        if (target == UUID.class) {
            if (!(plain instanceof String)) { throw failure(ctx, "Expected UUID text"); }
            try { return UUID.fromString((String) plain); }
            catch (IllegalArgumentException e) { throw failure(ctx, "Invalid UUID text"); }
        }
        if (!(plain instanceof Number) && !(plain instanceof String)) { throw failure(ctx, "Expected a number or numeric text"); }
        try {
            if (target == Double.class) {
                double value = Double.parseDouble(plain.toString());
                if (!Double.isFinite(value) && !nonfinite(plain)) { throw failure(ctx, "Number overflows double"); }
                return value;
            }
            if (target == Float.class) {
                float value = Float.parseFloat(plain.toString());
                if (!Float.isFinite(value)) {
                    if (nonfinite(plain)) { return value; }
                    throw failure(ctx, "Number overflows float");
                }
                if (decimal(plain).compareTo(new BigDecimal(Float.toString(value))) != 0) {
                    throw failure(ctx, "Decimal does not print back identically as float");
                }
                return value;
            }
            BigDecimal number = decimal(plain);
            if (target == BigDecimal.class) { return number; }
            BigInteger integer = number.toBigIntegerExact();
            if (target == BigInteger.class) { return integer; }
            if (target == Byte.class) { return integer.byteValueExact(); }
            if (target == Short.class) { return integer.shortValueExact(); }
            if (target == Integer.class) { return integer.intValueExact(); }
            if (target == Long.class) { return integer.longValueExact(); }
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ConversionException("Number is not exactly representable as " + target.getName(),
                    ctx.file(), ctx.path(), ctx.declaredType(), e);
        }
        throw failure(ctx, "Unsupported scalar type " + target.getName());
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
    }
    private static boolean nonfinite(Object value) {
        if (value instanceof Float || value instanceof Double) { return !Double.isFinite(((Number) value).doubleValue()); }
        return "NaN".equals(value) || "Infinity".equals(value) || "+Infinity".equals(value) || "-Infinity".equals(value);
    }
    private static ConversionException failure(ConversionContext ctx, String message) {
        return new ConversionException(message, ctx.file(), ctx.path(), ctx.declaredType());
    }
}
