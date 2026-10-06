package com.ultikits.ultitools.abstracts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mockito.MockedStatic;

import com.ultikits.ultitools.annotations.config.NotEmpty;
import com.ultikits.ultitools.annotations.config.Range;
import com.ultikits.ultitools.config.convert.ConfigConverter;
import com.ultikits.ultitools.config.convert.ConfigConverterFor;
import com.ultikits.ultitools.config.convert.ConversionContext;
import com.ultikits.ultitools.config.convert.ConversionException;
import com.ultikits.ultitools.config.convert.ConverterRegistry;
import com.ultikits.ultitools.utils.PackageScanUtils;

/**
 * Test-only module value types for the constraint tests of plan 17-76: an element type whose fields carry constraint
 * annotations, bound by a module converter - the shape of UltiRecipe's {@code RecipeConfig.OutputItem}, which the
 * framework never validates.
 */
final class ConstraintFixtures {

    private ConstraintFixtures() {
    }

    /** A module value type with constrained fields, reached through a declared setting. */
    public static final class Item {
        @NotEmpty
        String material;

        @Range(min = 1, max = 64)
        int amount;

        Item(String material, int amount) {
            this.material = material;
            this.amount = amount;
        }
    }

    /** A value type holding an {@link Item}: the constraint is two levels below the setting. */
    public static final class Recipe {
        Item output;

        Recipe(Item output) {
            this.output = output;
        }
    }

    /** Binds {@link Item} from {@code {material, amount}}, accepting any value as UltiRecipe's own converter does. */
    @ConfigConverterFor(Item.class)
    public static class ItemConverter implements ConfigConverter<Item> {
        @Override
        public Object toPlain(Item value, ConversionContext ctx) {
            Map<String, Object> plain = new LinkedHashMap<>();
            plain.put("material", value.material);
            plain.put("amount", value.amount);
            return plain;
        }

        @Override
        public Item fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
            if (!(plain instanceof Map)) {
                throw new ConversionException("Expected a map", ctx.file(), ctx.path(), ctx.declaredType());
            }
            Map<?, ?> map = (Map<?, ?>) plain;
            Object amount = map.get("amount");
            return new Item(String.valueOf(map.get("material")), amount instanceof Number ? ((Number) amount).intValue() : 0);
        }
    }

    /** Binds {@link Recipe} from {@code {output: {material, amount}}}. */
    @ConfigConverterFor(Recipe.class)
    public static class RecipeConverter implements ConfigConverter<Recipe> {
        private final ItemConverter items = new ItemConverter();

        @Override
        public Object toPlain(Recipe value, ConversionContext ctx) {
            return Collections.singletonMap("output", items.toPlain(value.output, ctx));
        }

        @Override
        public Recipe fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
            if (!(plain instanceof Map)) {
                throw new ConversionException("Expected a map", ctx.file(), ctx.path(), ctx.declaredType());
            }
            return new Recipe(items.fromPlain(((Map<?, ?>) plain).get("output"), ctx));
        }
    }

    /** Prepares {@code plugin}'s converter registry with the two module converters above, as module discovery would. */
    static void prepareModuleConverters(UltiToolsPlugin plugin) {
        try (MockedStatic<PackageScanUtils> scanner = mockStatic(PackageScanUtils.class)) {
            scanner.when(() -> PackageScanUtils.scanAnnotatedClasses(any(), anyString(), any()))
                    .thenAnswer(call -> {
                        Class<? extends Annotation> annotation = call.getArgument(0);
                        return annotation == ConfigConverterFor.class
                                ? new java.util.LinkedHashSet<Class<?>>(java.util.Arrays.asList(ItemConverter.class,
                                        RecipeConverter.class))
                                : Collections.emptySet();
                    });
            ConverterRegistry.prepareModule(plugin, new String[]{"fixture.constraints"},
                    ConstraintFixtures.class.getClassLoader());
        }
    }
}
