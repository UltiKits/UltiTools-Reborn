package com.ultikits.ultitools.abstracts.command.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The registry owns only explicitly registered module parsers in 7.0. */
class TypeParserRegistryTest {
    private final TypeParserRegistry registry = TypeParserRegistry.getInstance();
    private final List<TypeParser<?>> registered = new ArrayList<>();

    @AfterEach void releaseParsers() { registered.forEach(registry::unregister); }

    @Test void singletonIsConsistent() {
        assertThat(TypeParserRegistry.getInstance()).isSameAs(registry);
    }

    @Test void builtInTypesAreNoLongerRegisteredImplicitly() {
        for (Class<?> type : List.of(String.class, Integer.class, int.class, Boolean.class,
                Double.class, Long.class, Float.class, Short.class, Byte.class, java.util.UUID.class)) {
            assertThat(registry.hasParser(type)).as(type.getName()).isFalse();
        }
    }

    @Test void registersParsesAndUnregistersACustomType() throws TypeParseException {
        TypeParser<Value> parser = register(parser(Value.class, Value::new, 0));
        assertThat(registry.hasParser(Value.class)).isTrue();
        assertThat(registry.parse("hello", Value.class)).isEqualTo(new Value("hello"));
        assertThat(registry.getAllParsers()).contains(parser);
        registry.unregister(parser);
        assertThat(registry.hasParser(Value.class)).isFalse();
    }

    @Test void explicitlyRegisteredParsersAlsoParseArrays() throws TypeParseException {
        register(parser(Value.class, Value::new, 0));
        assertThat(registry.parseArray(new String[]{"a", "b"}, Value.class))
                .containsExactly(new Value("a"), new Value("b"));
    }

    @Test void missingSingleAndArrayParsersAreReported() {
        assertThatThrownBy(() -> registry.parse("value", Object.class)).isInstanceOf(TypeParseException.class);
        assertThatThrownBy(() -> registry.parseArray(new String[]{"value"}, Object.class))
                .isInstanceOf(TypeParseException.class);
    }

    @Test void customParserFailuresRetainTheirCauseAndMessage() {
        TypeParseException expected = new TypeParseException("bad", Value.class, "module detail");
        register(new TypeParser<Value>() {
            @Override public Class<Value> getPrimaryType() { return Value.class; }
            @Override public List<Class<?>> getSupportedTypes() { return List.of(Value.class); }
            @Override public Value parse(String value) throws TypeParseException { throw expected; }
        });
        assertThatThrownBy(() -> registry.parse("bad", Value.class)).isSameAs(expected);
    }

    @Test void registeredPriorityOrderIsDescending() throws TypeParseException {
        TypeParser<Value> low = register(parser(Value.class, value -> new Value("low"), 0));
        TypeParser<Value> high = register(parser(Value.class, value -> new Value("high"), 10));
        assertThat(registry.parse("value", Value.class)).isEqualTo(new Value("high"));
        assertThat(registry.getAllParsers()).containsSubsequence(high, low);
    }

    @Test void supportedAliasesAreAvailableOnlyDuringRegistration() {
        TypeParser<Value> parser = register(new TypeParser<Value>() {
            @Override public Class<Value> getPrimaryType() { return Value.class; }
            @Override public List<Class<?>> getSupportedTypes() { return List.of(Value.class, Alias.class); }
            @Override public Value parse(String value) { return new Value(value); }
        });
        assertThat(registry.getParser(Alias.class)).isSameAs(parser);
        registry.unregister(parser);
        assertThat(registry.hasParser(Alias.class)).isFalse();
    }

    @Test void inheritedTypesUseTheRegisteredParentParser() {
        TypeParser<Parent> parser = register(parser(Parent.class, value -> new Parent(), 0));
        assertThat(registry.getParser(Child.class)).isSameAs(parser);
    }

    @Test void rejectsNullAndIgnoresUnknownUnregistration() {
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
        assertDoesNotThrow(() -> registry.unregister(parser(Value.class, Value::new, 0)));
    }

    private <T> TypeParser<T> register(TypeParser<T> parser) {
        registry.register(parser);
        registered.add(parser);
        return parser;
    }

    private static <T> TypeParser<T> parser(Class<T> type, Function<String, T> parse, int priority) {
        return new TypeParser<T>() {
            @Override public Class<T> getPrimaryType() { return type; }
            @Override public List<Class<?>> getSupportedTypes() { return List.of(type); }
            @Override public T parse(String value) { return parse.apply(value); }
            @Override public int getPriority() { return priority; }
        };
    }

    record Value(String text) {}
    static class Alias { /* Explicit parser alias fixture. */ }
    static class Parent { /* Inheritance fixture. */ }
    static class Child extends Parent { /* Inheritance fixture. */ }
}
