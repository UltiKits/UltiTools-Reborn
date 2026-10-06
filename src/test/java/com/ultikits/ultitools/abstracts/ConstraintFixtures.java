package com.ultikits.ultitools.abstracts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mockito.MockedStatic;

import com.ultikits.ultitools.annotations.ConfigEntry;
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
@SuppressWarnings("unused") // the value types' fields are read reflectively by the declaration check
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

    /** A list type declared as a subclass of {@code ArrayList<Item>}: its element type comes from the superclass (PR #632). */
    public static class ItemList extends java.util.ArrayList<Item> {
        private static final long serialVersionUID = 1L;
    }

    /** A list type whose unconstrained element type comes from its superclass, for the binder agreement test (#633). */
    public static class PlainList extends java.util.ArrayList<Plain> {
        private static final long serialVersionUID = 1L;
    }

    /** A value type with no constrained field at all (gate-1 F6): a constraint on it is refused for its own type. */
    public static final class Plain {
        String label;

        Plain(String label) {
            this.label = label;
        }
    }

    /** A config class a value type points back to (gate-1 F1): its own entity checks its constraint. */
    public static class OwnerConfig extends AbstractConfigEntity {
        @Range(min = 1, max = 10)
        @ConfigEntry(path = "interval")
        int interval = 5;

        public OwnerConfig(String path) {
            super(path);
        }
    }

    /** A value type holding a back-reference to a config class (gate-1 F1). */
    public static final class OwnerLink {
        String name;
        OwnerConfig owner;

        OwnerLink(String name) {
            this.name = name;
        }
    }

    /** A value type with a transient cache of a constrained type, not part of the bound value (gate-1 F1). */
    public static final class TransientLink {
        String name;
        transient Item cached;

        TransientLink(String name) {
            this.name = name;
        }
    }

    /** Binds a value type from its {@code name} text, for the shapes above. */
    abstract static class NamedConverter<T> implements ConfigConverter<T> {
        abstract T named(String name);

        abstract String name(T value);

        @Override
        public Object toPlain(T value, ConversionContext ctx) {
            return name(value);
        }

        @Override
        public T fromPlain(Object plain, ConversionContext ctx) {
            return named(String.valueOf(plain));
        }
    }

    /** Binds {@link Plain}. */
    @ConfigConverterFor(Plain.class)
    public static class PlainConverter extends NamedConverter<Plain> {
        @Override Plain named(String name) { return new Plain(name); }
        @Override String name(Plain value) { return value.label; }
    }

    /** Binds {@link OwnerLink}. */
    @ConfigConverterFor(OwnerLink.class)
    public static class OwnerLinkConverter extends NamedConverter<OwnerLink> {
        @Override OwnerLink named(String name) { return new OwnerLink(name); }
        @Override String name(OwnerLink value) { return value.name; }
    }

    /** Binds {@link TransientLink}. */
    @ConfigConverterFor(TransientLink.class)
    public static class TransientLinkConverter extends NamedConverter<TransientLink> {
        @Override TransientLink named(String name) { return new TransientLink(name); }
        @Override String name(TransientLink value) { return value.name; }
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
                                        RecipeConverter.class, PlainConverter.class, OwnerLinkConverter.class,
                                        TransientLinkConverter.class))
                                : Collections.emptySet();
                    });
            ConverterRegistry.prepareModule(plugin, new String[]{"fixture.constraints"},
                    ConstraintFixtures.class.getClassLoader());
        }
    }
}
