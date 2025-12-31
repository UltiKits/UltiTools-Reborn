package com.ultikits.ultitools.interfaces;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link ObjectConfigSerializer} interface.
 */
@DisplayName("ObjectConfigSerializer Interface Tests")
class ObjectConfigSerializerTest {

    @Nested
    @DisplayName("Interface Structure Tests")
    class InterfaceStructureTests {

        @Test
        @DisplayName("Should be an interface")
        void shouldBeInterface() {
            assertThat(ObjectConfigSerializer.class.isInterface()).isTrue();
        }

        @Test
        @DisplayName("Should have generic type parameter T")
        void shouldHaveGenericTypeParameter() {
            TypeVariable<?>[] typeParams = ObjectConfigSerializer.class.getTypeParameters();
            assertThat(typeParams).hasSize(1);
            assertThat(typeParams[0].getName()).isEqualTo("T");
        }

        @Test
        @DisplayName("Should have serializeToMemorySection method")
        void shouldHaveSerializeToMemorySectionMethod() throws NoSuchMethodException {
            Method method = ObjectConfigSerializer.class.getMethod("serializeToMemorySection", Object.class);
            assertThat(method).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("serializeToMemorySection should return MemorySection")
        void serializeToMemorySectionShouldReturnMemorySection() throws NoSuchMethodException {
            Method method = ObjectConfigSerializer.class.getMethod("serializeToMemorySection", Object.class);
            assertThat(method.getReturnType()).isEqualTo(MemorySection.class);
        }

        @Test
        @DisplayName("serializeToMemorySection should accept generic type T")
        void serializeToMemorySectionShouldAcceptGenericType() throws NoSuchMethodException {
            Method method = ObjectConfigSerializer.class.getMethod("serializeToMemorySection", Object.class);
            Type[] paramTypes = method.getGenericParameterTypes();
            assertThat(paramTypes).hasSize(1);
            assertThat(paramTypes[0]).isInstanceOf(TypeVariable.class);
            assertThat(((TypeVariable<?>) paramTypes[0]).getName()).isEqualTo("T");
        }

        @Test
        @DisplayName("Should have exactly 1 method")
        void shouldHaveExactlyOneMethod() {
            Method[] methods = ObjectConfigSerializer.class.getDeclaredMethods();
            assertThat(methods).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Implementation Tests")
    class ImplementationTests {

        @Test
        @DisplayName("Simple string serializer should work")
        void simpleStringSerializerShouldWork() {
            ObjectConfigSerializer<String> serializer = new ObjectConfigSerializer<String>() {
                @Override
                public MemorySection serializeToMemorySection(String object) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("value", object);
                    // YamlConfiguration itself extends MemorySection
                    return config;
                }
            };

            MemorySection section = serializer.serializeToMemorySection("test value");
            assertThat(section).isNotNull();
            assertThat(section.getString("value")).isEqualTo("test value");
        }

        @Test
        @DisplayName("Complex object serializer should work")
        void complexObjectSerializerShouldWork() {
            class Person {
                final String name;
                final int age;
                final String email;

                Person(String name, int age, String email) {
                    this.name = name;
                    this.age = age;
                    this.email = email;
                }
            }

            ObjectConfigSerializer<Person> serializer = new ObjectConfigSerializer<Person>() {
                @Override
                public MemorySection serializeToMemorySection(Person person) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("name", person.name);
                    config.set("age", person.age);
                    config.set("email", person.email);
                    return config;
                }
            };

            Person person = new Person("John", 30, "john@example.com");
            MemorySection section = serializer.serializeToMemorySection(person);

            assertThat(section).isNotNull();
            assertThat(section.getString("name")).isEqualTo("John");
            assertThat(section.getInt("age")).isEqualTo(30);
            assertThat(section.getString("email")).isEqualTo("john@example.com");
        }

        @Test
        @DisplayName("Nested object serializer should work")
        void nestedObjectSerializerShouldWork() {
            class Address {
                final String street;
                final String city;

                Address(String street, String city) {
                    this.street = street;
                    this.city = city;
                }
            }

            ObjectConfigSerializer<Address> serializer = new ObjectConfigSerializer<Address>() {
                @Override
                public MemorySection serializeToMemorySection(Address address) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("street", address.street);
                    config.set("city", address.city);
                    return config;
                }
            };

            Address address = new Address("123 Main St", "New York");
            MemorySection section = serializer.serializeToMemorySection(address);

            assertThat(section).isNotNull();
            assertThat(section.getString("street")).isEqualTo("123 Main St");
            assertThat(section.getString("city")).isEqualTo("New York");
        }

        @Test
        @DisplayName("Serializer with list data should work")
        void serializerWithListDataShouldWork() {
            class ListContainer {
                final String name;
                final java.util.List<String> items;

                ListContainer(String name, java.util.List<String> items) {
                    this.name = name;
                    this.items = items;
                }
            }

            ObjectConfigSerializer<ListContainer> serializer = new ObjectConfigSerializer<ListContainer>() {
                @Override
                public MemorySection serializeToMemorySection(ListContainer container) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("name", container.name);
                    config.set("items", container.items);
                    return config;
                }
            };

            ListContainer container = new ListContainer("Shopping List",
                    java.util.Arrays.asList("Apple", "Banana", "Orange"));
            MemorySection section = serializer.serializeToMemorySection(container);

            assertThat(section).isNotNull();
            assertThat(section.getString("name")).isEqualTo("Shopping List");
            assertThat(section.getStringList("items")).containsExactly("Apple", "Banana", "Orange");
        }
    }

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Serializer can handle null values in object fields")
        void serializerCanHandleNullValuesInObjectFields() {
            class NullableData {
                final String required;
                final String optional;

                NullableData(String required, String optional) {
                    this.required = required;
                    this.optional = optional;
                }
            }

            ObjectConfigSerializer<NullableData> serializer = new ObjectConfigSerializer<NullableData>() {
                @Override
                public MemorySection serializeToMemorySection(NullableData data) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("required", data.required);
                    if (data.optional != null) {
                        config.set("optional", data.optional);
                    }
                    return config;
                }
            };

            NullableData dataWithNull = new NullableData("value", null);
            MemorySection section = serializer.serializeToMemorySection(dataWithNull);

            assertThat(section).isNotNull();
            assertThat(section.getString("required")).isEqualTo("value");
            assertThat(section.contains("optional")).isFalse();
        }
    }

    @Nested
    @DisplayName("Type Safety Tests")
    class TypeSafetyTests {

        @Test
        @DisplayName("Serializer preserves integer types")
        void serializerPreservesIntegerTypes() {
            ObjectConfigSerializer<Integer> serializer = new ObjectConfigSerializer<Integer>() {
                @Override
                public MemorySection serializeToMemorySection(Integer value) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("value", value);
                    return config;
                }
            };

            MemorySection section = serializer.serializeToMemorySection(42);
            assertThat(section.getInt("value")).isEqualTo(42);
        }

        @Test
        @DisplayName("Serializer preserves double types")
        void serializerPreservesDoubleTypes() {
            ObjectConfigSerializer<Double> serializer = new ObjectConfigSerializer<Double>() {
                @Override
                public MemorySection serializeToMemorySection(Double value) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("value", value);
                    return config;
                }
            };

            MemorySection section = serializer.serializeToMemorySection(3.14);
            assertThat(section.getDouble("value")).isEqualTo(3.14);
        }

        @Test
        @DisplayName("Serializer preserves boolean types")
        void serializerPreservesBooleanTypes() {
            ObjectConfigSerializer<Boolean> serializer = new ObjectConfigSerializer<Boolean>() {
                @Override
                public MemorySection serializeToMemorySection(Boolean value) {
                    YamlConfiguration config = new YamlConfiguration();
                    config.set("value", value);
                    return config;
                }
            };

            MemorySection sectionTrue = serializer.serializeToMemorySection(true);
            assertThat(sectionTrue.getBoolean("value")).isTrue();

            MemorySection sectionFalse = serializer.serializeToMemorySection(false);
            assertThat(sectionFalse.getBoolean("value")).isFalse();
        }
    }
}
