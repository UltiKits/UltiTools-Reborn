package com.ultikits.plugins.recipe.config;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("RecipeConfig Tests")
class RecipeConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have recipes enabled by default")
        void recipesEnabled() {
            RecipeConfig config = createRealConfig();
            assertThat(config.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have empty recipes map by default")
        void emptyRecipes() {
            RecipeConfig config = createRealConfig();
            assertThat(config.getRecipes()).isNotNull();
            assertThat(config.getRecipes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update enabled flag")
        void setEnabled() {
            RecipeConfig config = createRealConfig();
            config.setEnabled(false);
            assertThat(config.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update recipes map")
        void setRecipes() {
            RecipeConfig config = createRealConfig();
            Map<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
            RecipeConfig.RecipeDefinition def = new RecipeConfig.RecipeDefinition();
            recipes.put("test", def);

            config.setRecipes(recipes);

            assertThat(config.getRecipes()).hasSize(1);
            assertThat(config.getRecipes()).containsKey("test");
        }
    }

    @Nested
    @DisplayName("RecipeDefinition")
    class RecipeDefinitionTests {

        @Test
        @DisplayName("Should create with default empty collections")
        void defaultCollections() {
            RecipeConfig.RecipeDefinition def = new RecipeConfig.RecipeDefinition();

            assertThat(def.getOutput()).isNull();
            assertThat(def.getShape()).isEmpty();
            assertThat(def.getIngredients()).isEmpty();
        }

        @Test
        @DisplayName("Should set and get output")
        void setOutput() {
            RecipeConfig.RecipeDefinition def = new RecipeConfig.RecipeDefinition();
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setMaterial("DIAMOND");

            def.setOutput(output);

            assertThat(def.getOutput()).isSameAs(output);
            assertThat(def.getOutput().getMaterial()).isEqualTo("DIAMOND");
        }

        @Test
        @DisplayName("Should set and get shape")
        void setShape() {
            RecipeConfig.RecipeDefinition def = new RecipeConfig.RecipeDefinition();
            List<String> shape = new ArrayList<>();
            shape.add("xxx");
            shape.add("xyx");
            shape.add("xxx");

            def.setShape(shape);

            assertThat(def.getShape()).hasSize(3);
            assertThat(def.getShape().get(0)).isEqualTo("xxx");
        }

        @Test
        @DisplayName("Should set and get ingredients")
        void setIngredients() {
            RecipeConfig.RecipeDefinition def = new RecipeConfig.RecipeDefinition();
            Map<String, String> ingredients = new HashMap<>();
            ingredients.put("x", "DIAMOND");
            ingredients.put("y", "STICK");

            def.setIngredients(ingredients);

            assertThat(def.getIngredients()).hasSize(2);
            assertThat(def.getIngredients().get("x")).isEqualTo("DIAMOND");
        }
    }

    @Nested
    @DisplayName("OutputItem")
    class OutputItemTests {

        @Test
        @DisplayName("Should have default amount of 1")
        void defaultAmount() {
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            assertThat(output.getAmount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should set and get material")
        void setMaterial() {
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setMaterial("DIAMOND_SWORD");

            assertThat(output.getMaterial()).isEqualTo("DIAMOND_SWORD");
        }

        @Test
        @DisplayName("Should set and get amount")
        void setAmount() {
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setAmount(64);

            assertThat(output.getAmount()).isEqualTo(64);
        }

        @Test
        @DisplayName("Should set and get name")
        void setName() {
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setName("&bCustom Name");

            assertThat(output.getName()).isEqualTo("&bCustom Name");
        }

        @Test
        @DisplayName("Should set and get lore")
        void setLore() {
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            List<String> lore = new ArrayList<>();
            lore.add("&7Line 1");
            lore.add("&7Line 2");

            output.setLore(lore);

            assertThat(output.getLore()).hasSize(2);
            assertThat(output.getLore().get(0)).isEqualTo("&7Line 1");
        }

        @Test
        @DisplayName("Should handle null lore")
        void nullLore() {
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setLore(null);

            assertThat(output.getLore()).isNull();
        }
    }

    @Nested
    @DisplayName("initDefaults")
    class InitDefaults {

        @Test
        @DisplayName("Should create example recipe when recipes is null")
        void nullRecipes() {
            RecipeConfig config = createRealConfig();
            config.setRecipes(null);

            config.initDefaults();

            assertThat(config.getRecipes()).isNotNull();
            assertThat(config.getRecipes()).containsKey("golden_egg");
        }

        @Test
        @DisplayName("Should create example recipe when recipes is empty")
        void emptyRecipes() {
            RecipeConfig config = createRealConfig();
            config.setRecipes(new HashMap<>());

            config.initDefaults();

            assertThat(config.getRecipes()).containsKey("golden_egg");
        }

        @Test
        @DisplayName("Example recipe should have correct output")
        void exampleRecipeOutput() {
            RecipeConfig config = createRealConfig();
            config.setRecipes(null);

            config.initDefaults();

            RecipeConfig.RecipeDefinition recipe = config.getRecipes().get("golden_egg");
            assertThat(recipe).isNotNull();
            assertThat(recipe.getOutput()).isNotNull();
            assertThat(recipe.getOutput().getMaterial()).isEqualTo("EGG");
            assertThat(recipe.getOutput().getAmount()).isEqualTo(1);
            assertThat(recipe.getOutput().getName()).isEqualTo("&e&l金苹果蛋");
        }

        @Test
        @DisplayName("Example recipe should have correct lore")
        void exampleRecipeLore() {
            RecipeConfig config = createRealConfig();
            config.setRecipes(null);

            config.initDefaults();

            RecipeConfig.RecipeDefinition recipe = config.getRecipes().get("golden_egg");
            assertThat(recipe.getOutput().getLore()).hasSize(1);
            assertThat(recipe.getOutput().getLore().get(0)).contains("苹果");
        }

        @Test
        @DisplayName("Example recipe should have correct shape")
        void exampleRecipeShape() {
            RecipeConfig config = createRealConfig();
            config.setRecipes(null);

            config.initDefaults();

            RecipeConfig.RecipeDefinition recipe = config.getRecipes().get("golden_egg");
            assertThat(recipe.getShape()).hasSize(3);
            assertThat(recipe.getShape().get(0)).isEqualTo("xxx");
            assertThat(recipe.getShape().get(1)).isEqualTo("xyx");
            assertThat(recipe.getShape().get(2)).isEqualTo("y y");
        }

        @Test
        @DisplayName("Example recipe should have correct ingredients")
        void exampleRecipeIngredients() {
            RecipeConfig config = createRealConfig();
            config.setRecipes(null);

            config.initDefaults();

            RecipeConfig.RecipeDefinition recipe = config.getRecipes().get("golden_egg");
            assertThat(recipe.getIngredients()).hasSize(2);
            assertThat(recipe.getIngredients().get("x")).isEqualTo("APPLE");
            assertThat(recipe.getIngredients().get("y")).isEqualTo("DARK_OAK_WOOD");
        }

        @Test
        @DisplayName("Should not override existing recipes")
        void preserveExistingRecipes() {
            RecipeConfig config = createRealConfig();
            Map<String, RecipeConfig.RecipeDefinition> existing = new HashMap<>();
            RecipeConfig.RecipeDefinition custom = new RecipeConfig.RecipeDefinition();
            existing.put("custom", custom);
            config.setRecipes(existing);

            config.initDefaults();

            assertThat(config.getRecipes()).doesNotContainKey("golden_egg");
            assertThat(config.getRecipes()).containsKey("custom");
        }
    }

    /**
     * Create a real RecipeConfig using a mock path to avoid AbstractConfigEntity I/O.
     * We use Mockito spy to bypass the superclass constructor's file loading.
     */
    private RecipeConfig createRealConfig() {
        // Use mock to avoid AbstractConfigEntity file I/O, then set fields
        RecipeConfig config = mock(RecipeConfig.class, withSettings()
                .useConstructor("config/recipes.yml")
                .defaultAnswer(CALLS_REAL_METHODS));
        return config;
    }
}
