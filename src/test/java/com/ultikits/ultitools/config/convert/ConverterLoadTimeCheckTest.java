package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.exceptions.ConfigurationException;
import com.ultikits.ultitools.interfaces.impl.pasers.StringHashMapParser;

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
        @ConfigEntry(parser = StringHashMapParser.class)
        Map<String, RecipeDefinitionLike> value;
    }
    enum TestEnum { FIRST, SECOND }
}
