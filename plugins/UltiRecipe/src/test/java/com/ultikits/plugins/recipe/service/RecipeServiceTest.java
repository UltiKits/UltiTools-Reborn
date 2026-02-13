package com.ultikits.plugins.recipe.service;

import com.ultikits.plugins.recipe.UltiRecipeTestHelper;
import com.ultikits.plugins.recipe.config.RecipeConfig;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for RecipeService.
 * <p>
 * Note: RecipeService uses Bukkit.addRecipe() which requires mocking Bukkit static methods.
 * The plugin instance for NamespacedKey is injected via reflection to avoid mocking UltiTools.
 */
@DisplayName("RecipeService Tests")
class RecipeServiceTest {

    private RecipeService service;
    private RecipeConfig config;

    @BeforeEach
    void setUp() throws Exception {
        UltiRecipeTestHelper.setUp();

        config = UltiRecipeTestHelper.createDefaultConfig();
        service = new RecipeService();

        // Inject dependencies via reflection (normally done by @Autowired)
        UltiRecipeTestHelper.setField(service, "plugin", UltiRecipeTestHelper.getMockPlugin());
        UltiRecipeTestHelper.setField(service, "config", config);
        // Inject mock plugin for NamespacedKey creation
        UltiRecipeTestHelper.setField(service, "pluginInstance", UltiRecipeTestHelper.getMockJavaPlugin());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiRecipeTestHelper.tearDown();
    }

    // ==================== initRecipes ====================

    @Nested
    @DisplayName("initRecipes")
    class InitRecipes {

        @Test
        @DisplayName("Should return 0 when no recipes configured")
        void noRecipes() {
            when(config.getRecipes()).thenReturn(null);

            int count = service.initRecipes();

            assertThat(count).isZero();
            verify(UltiRecipeTestHelper.getMockLogger()).info("No custom recipes configured");
        }

        @Test
        @DisplayName("Should return 0 when recipes map is empty")
        void emptyRecipes() {
            when(config.getRecipes()).thenReturn(new HashMap<>());

            int count = service.initRecipes();

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("Should register valid recipes")
        void registerValidRecipes() throws Exception {
            RecipeConfig configWithRecipes = UltiRecipeTestHelper.createConfigWithSampleRecipes();
            UltiRecipeTestHelper.setField(service, "config", configWithRecipes);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class);
                 MockedStatic<Material> materialMock = mockStatic(Material.class)) {

                Server server = mock(Server.class);
                ItemFactory itemFactory = mock(ItemFactory.class);
                ItemMeta itemMeta = mock(ItemMeta.class);

                bukkitMock.when(Bukkit::getServer).thenReturn(server);
                bukkitMock.when(Bukkit::getItemFactory).thenReturn(itemFactory);
                bukkitMock.when(() -> Bukkit.addRecipe(any(ShapedRecipe.class))).thenReturn(true);

                when(itemFactory.getItemMeta(any(Material.class))).thenReturn(itemMeta);

                // Mock Material.matchMaterial for recipe ingredients
                materialMock.when(() -> Material.matchMaterial("DIAMOND")).thenReturn(Material.DIAMOND);
                materialMock.when(() -> Material.matchMaterial("COAL")).thenReturn(Material.COAL);
                materialMock.when(() -> Material.matchMaterial("STICK")).thenReturn(Material.STICK);

                int count = service.initRecipes();

                assertThat(count).isEqualTo(1);
                verify(UltiRecipeTestHelper.getMockLogger()).info(contains("Registered recipe"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("Should skip recipes with null output")
        void skipNullOutput() {
            RecipeConfig.RecipeDefinition recipe = new RecipeConfig.RecipeDefinition();
            recipe.setOutput(null);

            Map<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
            recipes.put("invalid", recipe);

            when(config.getRecipes()).thenReturn(recipes);

            int count = service.initRecipes();

            assertThat(count).isZero();
            verify(UltiRecipeTestHelper.getMockLogger()).warn(contains("Invalid recipe definition"));
        }

        @Test
        @DisplayName("Should skip recipes with null shape")
        void skipNullShape() {
            RecipeConfig.RecipeDefinition recipe = new RecipeConfig.RecipeDefinition();
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setMaterial("DIAMOND");
            recipe.setOutput(output);
            recipe.setShape(null);

            Map<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
            recipes.put("invalid", recipe);

            when(config.getRecipes()).thenReturn(recipes);

            int count = service.initRecipes();

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("Should skip recipes with invalid material")
        void skipInvalidMaterial() {
            RecipeConfig.RecipeDefinition recipe = new RecipeConfig.RecipeDefinition();
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setMaterial("INVALID_MATERIAL");
            recipe.setOutput(output);

            List<String> shape = Arrays.asList("xxx", "xxx", "xxx");
            recipe.setShape(shape);

            Map<String, String> ingredients = new HashMap<>();
            ingredients.put("x", "DIAMOND");
            recipe.setIngredients(ingredients);

            Map<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
            recipes.put("invalid", recipe);

            when(config.getRecipes()).thenReturn(recipes);

            try (MockedStatic<Material> materialMock = mockStatic(Material.class)) {

                materialMock.when(() -> Material.matchMaterial("INVALID_MATERIAL")).thenReturn(null);
                materialMock.when(() -> Material.matchMaterial("DIAMOND")).thenReturn(Material.DIAMOND);

                int count = service.initRecipes();

                assertThat(count).isZero();
                verify(UltiRecipeTestHelper.getMockLogger()).warn(contains("Invalid output material"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("Should handle exceptions during recipe registration")
        void handleException() throws Exception {
            RecipeConfig configWithRecipes = UltiRecipeTestHelper.createConfigWithSampleRecipes();

            // Create a spy to mock getPluginInstance() to throw exception
            RecipeService spyService = spy(new RecipeService());
            UltiRecipeTestHelper.setField(spyService, "plugin", UltiRecipeTestHelper.getMockPlugin());
            UltiRecipeTestHelper.setField(spyService, "config", configWithRecipes);
            doThrow(new RuntimeException("Test error")).when(spyService).getPluginInstance();

            int count = spyService.initRecipes();

            assertThat(count).isZero();
            verify(UltiRecipeTestHelper.getMockLogger()).warn(contains("Failed to register recipe"));
        }
    }

    // ==================== removeRecipes ====================

    @Nested
    @DisplayName("removeRecipes")
    class RemoveRecipes {

        @Test
        @DisplayName("Should remove all registered recipes")
        void removeAllRecipes() {
            // Inject some registered recipe keys
            Set<NamespacedKey> registeredRecipes = new HashSet<>();
            NamespacedKey key1 = mock(NamespacedKey.class);
            when(key1.getKey()).thenReturn("ultirecipe_test1");
            NamespacedKey key2 = mock(NamespacedKey.class);
            when(key2.getKey()).thenReturn("ultirecipe_test2");
            registeredRecipes.add(key1);
            registeredRecipes.add(key2);

            try {
                UltiRecipeTestHelper.setField(service, "registeredRecipes", registeredRecipes);

                try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                    bukkitMock.when(() -> Bukkit.removeRecipe(any(NamespacedKey.class))).thenReturn(true);

                    service.removeRecipes();

                    bukkitMock.verify(() -> Bukkit.removeRecipe(key1));
                    bukkitMock.verify(() -> Bukkit.removeRecipe(key2));
                    verify(UltiRecipeTestHelper.getMockLogger(), times(2)).info(contains("Removed recipe"));

                    @SuppressWarnings("unchecked")
                    Set<NamespacedKey> afterRemoval = (Set<NamespacedKey>) UltiRecipeTestHelper.getField(service, "registeredRecipes");
                    assertThat(afterRemoval).isEmpty();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("Should handle empty registered recipes")
        void emptyRegisteredRecipes() {
            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
                service.removeRecipes();

                bukkitMock.verifyNoInteractions();
            }
        }
    }

    // ==================== reloadRecipes ====================

    @Nested
    @DisplayName("reloadRecipes")
    class ReloadRecipes {

        @Test
        @DisplayName("Should remove old recipes and init new ones")
        void reloadRecipes() {
            RecipeService spyService = spy(service);
            doReturn(5).when(spyService).initRecipes();
            doNothing().when(spyService).removeRecipes();

            int count = spyService.reloadRecipes();

            assertThat(count).isEqualTo(5);
            verify(spyService).removeRecipes();
            verify(spyService).initRecipes();
        }

        @Test
        @DisplayName("Should return 0 if no new recipes")
        void reloadNoRecipes() {
            RecipeService spyService = spy(service);
            doReturn(0).when(spyService).initRecipes();
            doNothing().when(spyService).removeRecipes();

            int count = spyService.reloadRecipes();

            assertThat(count).isZero();
        }
    }

    // ==================== getRecipeList ====================

    @Nested
    @DisplayName("getRecipeList")
    class GetRecipeList {

        @Test
        @DisplayName("Should return list of recipe names")
        void getRecipeNames() {
            Set<NamespacedKey> registeredRecipes = new HashSet<>();
            NamespacedKey key1 = mock(NamespacedKey.class);
            when(key1.getKey()).thenReturn("ultirecipe_custom_diamond");
            NamespacedKey key2 = mock(NamespacedKey.class);
            when(key2.getKey()).thenReturn("ultirecipe_golden_egg");
            registeredRecipes.add(key1);
            registeredRecipes.add(key2);

            try {
                UltiRecipeTestHelper.setField(service, "registeredRecipes", registeredRecipes);

                List<String> names = service.getRecipeList();

                assertThat(names).hasSize(2);
                assertThat(names).contains("custom_diamond", "golden_egg");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("Should return empty list when no recipes")
        void emptyList() {
            List<String> names = service.getRecipeList();

            assertThat(names).isEmpty();
        }
    }

    // ==================== getRecipeCount ====================

    @Nested
    @DisplayName("getRecipeCount")
    class GetRecipeCount {

        @Test
        @DisplayName("Should return count of registered recipes")
        void getCount() {
            Set<NamespacedKey> registeredRecipes = new HashSet<>();
            registeredRecipes.add(mock(NamespacedKey.class));
            registeredRecipes.add(mock(NamespacedKey.class));
            registeredRecipes.add(mock(NamespacedKey.class));

            try {
                UltiRecipeTestHelper.setField(service, "registeredRecipes", registeredRecipes);

                int count = service.getRecipeCount();

                assertThat(count).isEqualTo(3);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("Should return 0 when no recipes")
        void zeroCount() {
            int count = service.getRecipeCount();

            assertThat(count).isZero();
        }
    }

    // ==================== Shape Validation ====================

    @Nested
    @DisplayName("Shape Validation")
    class ShapeValidation {

        @Test
        @DisplayName("Should reject shape with wrong number of rows")
        void wrongShapeSize() {
            RecipeConfig.RecipeDefinition recipe = new RecipeConfig.RecipeDefinition();
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setMaterial("DIAMOND");
            recipe.setOutput(output);

            // Only 2 rows instead of 3
            List<String> shape = Arrays.asList("xxx", "xxx");
            recipe.setShape(shape);

            Map<String, String> ingredients = new HashMap<>();
            ingredients.put("x", "COAL");
            recipe.setIngredients(ingredients);

            Map<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
            recipes.put("invalid_shape", recipe);

            when(config.getRecipes()).thenReturn(recipes);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class);
                 MockedStatic<Material> materialMock = mockStatic(Material.class)) {

                Server server = mock(Server.class);
                ItemFactory itemFactory = mock(ItemFactory.class);
                ItemMeta itemMeta = mock(ItemMeta.class);

                bukkitMock.when(Bukkit::getServer).thenReturn(server);
                bukkitMock.when(Bukkit::getItemFactory).thenReturn(itemFactory);
                when(itemFactory.getItemMeta(any(Material.class))).thenReturn(itemMeta);

                materialMock.when(() -> Material.matchMaterial("DIAMOND")).thenReturn(Material.DIAMOND);
                materialMock.when(() -> Material.matchMaterial("COAL")).thenReturn(Material.COAL);

                int count = service.initRecipes();

                assertThat(count).isZero();
                verify(UltiRecipeTestHelper.getMockLogger()).warn(contains("exactly 3 rows"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ==================== Ingredient Validation ====================

    @Nested
    @DisplayName("Ingredient Validation")
    class IngredientValidation {

        @Test
        @DisplayName("Should warn on multi-character ingredient key")
        void multiCharKey() {
            RecipeConfig.RecipeDefinition recipe = new RecipeConfig.RecipeDefinition();
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setMaterial("DIAMOND");
            recipe.setOutput(output);

            List<String> shape = Arrays.asList("xxx", "xxx", "xxx");
            recipe.setShape(shape);

            Map<String, String> ingredients = new HashMap<>();
            ingredients.put("xx", "COAL"); // Invalid: 2 characters
            recipe.setIngredients(ingredients);

            Map<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
            recipes.put("multi_char", recipe);

            when(config.getRecipes()).thenReturn(recipes);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class);
                 MockedStatic<Material> materialMock = mockStatic(Material.class)) {

                Server server = mock(Server.class);
                ItemFactory itemFactory = mock(ItemFactory.class);
                ItemMeta itemMeta = mock(ItemMeta.class);

                bukkitMock.when(Bukkit::getServer).thenReturn(server);
                bukkitMock.when(Bukkit::getItemFactory).thenReturn(itemFactory);
                bukkitMock.when(() -> Bukkit.addRecipe(any(ShapedRecipe.class))).thenReturn(true);
                when(itemFactory.getItemMeta(any(Material.class))).thenReturn(itemMeta);

                materialMock.when(() -> Material.matchMaterial("DIAMOND")).thenReturn(Material.DIAMOND);

                service.initRecipes();

                verify(UltiRecipeTestHelper.getMockLogger()).warn(contains("single character"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("Should warn on unknown ingredient material")
        void unknownMaterial() {
            RecipeConfig.RecipeDefinition recipe = new RecipeConfig.RecipeDefinition();
            RecipeConfig.OutputItem output = new RecipeConfig.OutputItem();
            output.setMaterial("DIAMOND");
            recipe.setOutput(output);

            List<String> shape = Arrays.asList("xxx", "xxx", "xxx");
            recipe.setShape(shape);

            Map<String, String> ingredients = new HashMap<>();
            ingredients.put("x", "UNKNOWN_MATERIAL");
            recipe.setIngredients(ingredients);

            Map<String, RecipeConfig.RecipeDefinition> recipes = new HashMap<>();
            recipes.put("unknown_mat", recipe);

            when(config.getRecipes()).thenReturn(recipes);

            try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class);
                 MockedStatic<Material> materialMock = mockStatic(Material.class)) {

                Server server = mock(Server.class);
                ItemFactory itemFactory = mock(ItemFactory.class);
                ItemMeta itemMeta = mock(ItemMeta.class);

                bukkitMock.when(Bukkit::getServer).thenReturn(server);
                bukkitMock.when(Bukkit::getItemFactory).thenReturn(itemFactory);
                bukkitMock.when(() -> Bukkit.addRecipe(any(ShapedRecipe.class))).thenReturn(true);
                when(itemFactory.getItemMeta(any(Material.class))).thenReturn(itemMeta);

                materialMock.when(() -> Material.matchMaterial("DIAMOND")).thenReturn(Material.DIAMOND);
                materialMock.when(() -> Material.matchMaterial("UNKNOWN_MATERIAL")).thenReturn(null);

                service.initRecipes();

                verify(UltiRecipeTestHelper.getMockLogger()).warn(contains("Unknown material"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
