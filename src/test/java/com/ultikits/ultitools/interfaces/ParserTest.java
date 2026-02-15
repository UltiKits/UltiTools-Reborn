package com.ultikits.ultitools.interfaces;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link Parser} interface.
 */
@DisplayName("Parser Interface Tests")
class ParserTest {

    @Nested
    @DisplayName("Interface Structure Tests")
    class InterfaceStructureTests {

        @Test
        @DisplayName("Should be an interface")
        void shouldBeInterface() {
            assertThat(Parser.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("Should have generic type parameter T")
        void shouldHaveGenericTypeParameter() {
            TypeVariable<?>[] typeParams = Parser.class.getTypeParameters();
            assertThat(typeParams).hasSize(1);
            assertThat(typeParams[0].getName()).isEqualTo("T");
        }

        @Test
        @DisplayName("Should have parse method")
        void shouldHaveParseMethod() throws NoSuchMethodException {
            Method parseMethod = Parser.class.getMethod("parse", Object.class);
            assertThat(parseMethod).isNotNull();
            assertThat(Modifier.isPublic(parseMethod.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(parseMethod.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("Should have serialize method")
        void shouldHaveSerializeMethod() throws NoSuchMethodException {
            Method serializeMethod = Parser.class.getMethod("serialize", Object.class);
            assertThat(serializeMethod).isNotNull();
            assertThat(Modifier.isPublic(serializeMethod.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(serializeMethod.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("parse method should return generic type T")
        void parseMethodShouldReturnGenericType() throws NoSuchMethodException {
            Method parseMethod = Parser.class.getMethod("parse", Object.class);
            Type returnType = parseMethod.getGenericReturnType();
            assertThat(returnType).isInstanceOf(TypeVariable.class);
            assertThat(((TypeVariable<?>) returnType).getName()).isEqualTo("T");
        }

        @Test
        @DisplayName("serialize method should accept generic type T")
        void serializeMethodShouldAcceptGenericType() throws NoSuchMethodException {
            Method serializeMethod = Parser.class.getMethod("serialize", Object.class);
            Type[] paramTypes = serializeMethod.getGenericParameterTypes();
            assertThat(paramTypes).hasSize(1);
            assertThat(paramTypes[0]).isInstanceOf(TypeVariable.class);
            assertThat(((TypeVariable<?>) paramTypes[0]).getName()).isEqualTo("T");
        }

        @Test
        @DisplayName("serialize method should return Object")
        void serializeMethodShouldReturnObject() throws NoSuchMethodException {
            Method serializeMethod = Parser.class.getMethod("serialize", Object.class);
            assertThat(serializeMethod.getReturnType()).isEqualTo(Object.class);
        }
    }

    @Nested
    @DisplayName("Implementation Tests")
    class ImplementationTests {

        @Test
        @DisplayName("Simple String parser implementation should work")
        void simpleStringParserShouldWork() {
            Parser<String> stringParser = new Parser<String>() {
                @Override
                public String parse(Object object) {
                    return String.valueOf(object);
                }

                @Override
                public Object serialize(String object) {
                    return object;
                }
            };

            assertThat(stringParser.parse(123)).isEqualTo("123");
            assertThat(stringParser.parse("hello")).isEqualTo("hello");
            assertThat(stringParser.serialize("test")).isEqualTo("test");
        }

        @Test
        @DisplayName("Integer parser implementation should work")
        void integerParserShouldWork() {
            Parser<Integer> intParser = new Parser<Integer>() {
                @Override
                public Integer parse(Object object) {
                    if (object instanceof Number) {
                        return ((Number) object).intValue();
                    }
                    return Integer.parseInt(String.valueOf(object));
                }

                @Override
                public Object serialize(Integer object) {
                    return object;
                }
            };

            assertThat(intParser.parse(42)).isEqualTo(42);
            assertThat(intParser.parse("123")).isEqualTo(123);
            assertThat(intParser.parse(3.14)).isEqualTo(3);
            assertThat(intParser.serialize(100)).isEqualTo(100);
        }

        @Test
        @DisplayName("Map parser implementation should work")
        void mapParserShouldWork() {
            Parser<Map<String, Object>> mapParser = new Parser<Map<String, Object>>() {
                @Override
                public Map<String, Object> parse(Object object) {
                    if (object instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = (Map<String, Object>) object;
                        return result;
                    }
                    return new HashMap<>();
                }

                @Override
                public Object serialize(Map<String, Object> object) {
                    return new HashMap<>(object);
                }
            };

            Map<String, Object> input = new HashMap<>();
            input.put("key1", "value1");
            input.put("key2", 123);

            Map<String, Object> parsed = mapParser.parse(input);
            assertThat(parsed).containsEntry("key1", "value1");
            assertThat(parsed).containsEntry("key2", 123);

            Object serialized = mapParser.serialize(input);
            assertThat(serialized).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("Parse null should be handled by implementation")
        void parseNullShouldBeHandledByImplementation() {
            Parser<String> nullSafeParser = new Parser<String>() {
                @Override
                public String parse(Object object) {
                    return object == null ? "" : String.valueOf(object);
                }

                @Override
                public Object serialize(String object) {
                    return object;
                }
            };

            assertThat(nullSafeParser.parse(null)).isEqualTo("");
        }

        @Test
        @DisplayName("Custom object parser should work")
        void customObjectParserShouldWork() {
            // Test with a simple data class
            class Point {
                final int x;
                final int y;

                Point(int x, int y) {
                    this.x = x;
                    this.y = y;
                }
            }

            Parser<Point> pointParser = new Parser<Point>() {
                @Override
                public Point parse(Object object) {
                    if (object instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) object;
                        int x = ((Number) map.get("x")).intValue();
                        int y = ((Number) map.get("y")).intValue();
                        return new Point(x, y);
                    }
                    return new Point(0, 0);
                }

                @Override
                public Object serialize(Point object) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("x", object.x);
                    map.put("y", object.y);
                    return map;
                }
            };

            Map<String, Object> pointData = new HashMap<>();
            pointData.put("x", 10);
            pointData.put("y", 20);

            Point parsed = pointParser.parse(pointData);
            assertThat(parsed.x).isEqualTo(10);
            assertThat(parsed.y).isEqualTo(20);

            Object serialized = pointParser.serialize(parsed);
            assertThat(serialized).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> serializedMap = (Map<String, Object>) serialized;
            assertThat(serializedMap).containsEntry("x", 10);
            assertThat(serializedMap).containsEntry("y", 20);
        }
    }

    @Nested
    @DisplayName("Bidirectional Tests")
    class BidirectionalTests {

        @Test
        @DisplayName("Parse and serialize should be reversible for String")
        void parseAndSerializeShouldBeReversibleForString() {
            Parser<String> parser = new Parser<String>() {
                @Override
                public String parse(Object object) {
                    return String.valueOf(object);
                }

                @Override
                public Object serialize(String object) {
                    return object;
                }
            };

            String original = "test data";
            Object serialized = parser.serialize(original);
            String parsed = parser.parse(serialized);
            assertThat(parsed).isEqualTo(original);
        }

        @Test
        @DisplayName("Parse and serialize should be reversible for Integer")
        void parseAndSerializeShouldBeReversibleForInteger() {
            Parser<Integer> parser = new Parser<Integer>() {
                @Override
                public Integer parse(Object object) {
                    return Integer.parseInt(String.valueOf(object));
                }

                @Override
                public Object serialize(Integer object) {
                    return String.valueOf(object);
                }
            };

            Integer original = 42;
            Object serialized = parser.serialize(original);
            Integer parsed = parser.parse(serialized);
            assertThat(parsed).isEqualTo(original);
        }
    }
}
