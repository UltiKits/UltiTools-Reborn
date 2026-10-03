package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.exceptions.ConfigurationException;

class ConverterLoadTimeCheckTest {
    @TempDir
    Path directory;

    @Test
    void unknownNestedTypeFailsWithExactActionableMessageBeforeFileExists() {
        Path file = directory.resolve("config/recipes.yml");
        assertThatThrownBy(() -> ConverterRegistry.framework().checkEntityFields(
                Recipes.class, "FixtureModule", "config/recipes.yml"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Module FixtureModule, file config/recipes.yml, key \"recipes\": no config converter for "
                        + RecipeDefinitionLike.class.getName()
                        + " (declared as Map<String, RecipeDefinitionLike>). Register one with "
                        + "@ConfigConverterFor(RecipeDefinitionLike.class) in the module's scan packages, "
                        + "or declare the value type as Map<String, Object>.");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void unknownTypesAreCheckedInsideEveryTypeArgumentAndInheritedField() {
        assertThatThrownBy(() -> ConverterRegistry.framework().checkEntityFields(
                ChildRecipes.class, "FixtureModule", "config/recipes.yml"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(RecipeDefinitionLike.class.getName());
        assertThatThrownBy(() -> ConverterRegistry.framework().checkEntityFields(
                Nested.class, "FixtureModule", "config/nested.yml"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("List<Map<String, RecipeDefinitionLike>>");
    }

    @Test
    void plainObjectSlotsRawCollectionsArraysEnumsAndBoundsPass() {
        assertThatCode(() -> ConverterRegistry.framework().checkEntityFields(
                PlainShapes.class, "FixtureModule", "config/plain.yml"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ConverterRegistry.framework().checkEntityFields(
                Bounds.class, "FixtureModule", "config/bounds.yml"))
                .doesNotThrowAnyException();
    }

    @Test
    void customRegistrationResolvesUnknownAndLegacyOverridesUseTheirAdapter() {
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        registry.register(RecipeDefinitionLike.class,
                new ConverterRegistryLookupTest.MarkerConverter<RecipeDefinitionLike>(), false);
        assertThatCode(() -> registry.checkEntityFields(Recipes.class, "FixtureModule", "config/recipes.yml"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ConverterRegistry.framework().checkEntityFields(
                Legacy.class, "FixtureModule", "config/legacy.yml"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("unsupportedInheritedShapes")
    void concreteEntityAndContainerArgumentsFailIndependently(Class<?> entity) {
        assertThatThrownBy(() -> ConverterRegistry.framework().checkEntityFields(entity, "FixtureModule", "config/generic.yml"))
                .isInstanceOf(ConfigurationException.class).hasMessageContaining(RecipeDefinitionLike.class.getName());
        assertThat(directory.resolve("config/generic.yml")).doesNotExist();
    }

    static Stream<Class<?>> unsupportedInheritedShapes() {
        return Stream.of(UnknownChild.class, UnknownListChild.class, UnknownArrayChild.class,
                UnknownNestedChild.class, ConcreteListField.class, ConcreteMapKeyField.class,
                ConcreteMapValueField.class, ConcreteNestedField.class, ConcreteWildcardField.class);
    }

    @Test
    void boundedInheritedStringUsesConcreteArgumentRatherThanComparableBound() {
        assertThatCode(() -> ConverterRegistry.framework().checkEntityFields(StringChild.class, "FixtureModule", "config/string.yml"))
                .doesNotThrowAnyException();
    }

    @Test
    void inheritedUnknownDiagnosticDescribesResolvedDeclaredType() {
        assertThatThrownBy(() -> ConverterRegistry.framework().checkEntityFields(UnknownListChild.class, "FixtureModule", "config/list.yml"))
                .hasMessageContaining("declared as List<RecipeDefinitionLike>");
    }

    @Test
    void supportedConcreteContainersAndRegisteredContainerOwnershipRemainValid() {
        assertThatCode(() -> ConverterRegistry.framework().checkEntityFields(SupportedConcrete.class, "FixtureModule", "config/concrete.yml"))
                .doesNotThrowAnyException();
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        registry.register(UnknownList.class, new ConverterRegistryLookupTest.MarkerConverter<UnknownList>(), true);
        assertThatCode(() -> registry.checkEntityFields(ConcreteListField.class, "FixtureModule", "config/custom.yml"))
                .doesNotThrowAnyException();
    }

    static class GenericBase<T> { @ConfigEntry T value; }
    static class GenericListBase<T> { @ConfigEntry List<T> values; }
    static class GenericArrayBase<T> { @ConfigEntry T[] values; }
    static class GenericNestedBase<T> { @ConfigEntry Map<String, List<T[]>> values; }
    static class UnknownChild extends GenericBase<RecipeDefinitionLike> { }
    static class UnknownListChild extends GenericListBase<RecipeDefinitionLike> { }
    static class UnknownArrayChild extends GenericArrayBase<RecipeDefinitionLike> { }
    static class UnknownNestedChild extends GenericNestedBase<RecipeDefinitionLike> { }
    static class BoundedBase<T extends Comparable<T>> { @ConfigEntry T value; }
    static class StringChild extends BoundedBase<String> { }
    public static class UnknownList extends ArrayList<RecipeDefinitionLike> { }
    public static class UnknownKeyMap extends LinkedHashMap<RecipeDefinitionLike, String> { }
    public static class UnknownValueMap extends LinkedHashMap<String, RecipeDefinitionLike> { }
    public static class NestedList extends ArrayList<Map<String, RecipeDefinitionLike[]>> { }
    public static class WildcardList extends ArrayList<List<? extends RecipeDefinitionLike>> { }
    public static class StringList extends ArrayList<String> { }
    public static class UuidMap extends LinkedHashMap<String, UUID> { }
    static class ConcreteListField { @ConfigEntry UnknownList values; }
    static class ConcreteMapKeyField { @ConfigEntry UnknownKeyMap values; }
    static class ConcreteMapValueField { @ConfigEntry UnknownValueMap values; }
    static class ConcreteNestedField { @ConfigEntry NestedList values; }
    static class ConcreteWildcardField { @ConfigEntry WildcardList values; }
    static class SupportedConcrete { @ConfigEntry StringList texts; @ConfigEntry UuidMap ids; }

    static class RecipeDefinitionLike { }
    static class Recipes {
        @ConfigEntry(path = "recipes")
        Map<String, RecipeDefinitionLike> recipes;
    }
    static class ChildRecipes extends Recipes { }
    static class Nested {
        @ConfigEntry
        List<Map<String, RecipeDefinitionLike>> entries;
    }
    @SuppressWarnings("rawtypes")
    static class PlainShapes {
        @ConfigEntry Object value;
        @ConfigEntry Map<String, Object> map;
        @ConfigEntry List<Object> list;
        @ConfigEntry List raw;
        @ConfigEntry String[] array;
        @ConfigEntry TestEnum mode;
    }
    static class Bounds<T extends String, U extends Enum<U>> {
        @ConfigEntry List<? extends String> strings;
        @ConfigEntry T text;
        @ConfigEntry List<U> enums;
    }
    static class Legacy {
        // Deliberately verifies that a legacy override owns its unknown value type.
        @SuppressWarnings("removal")
        @ConfigEntry(parser = com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser.class)
        Map<String, RecipeDefinitionLike> value;
    }
    enum TestEnum { FIRST, SECOND }
}
