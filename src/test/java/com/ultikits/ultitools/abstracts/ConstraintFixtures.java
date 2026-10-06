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

    /** The type argument a wrapper converter binds its content as (the declared one, or Object for a raw declaration). */
    static java.lang.reflect.Type contentType(ConversionContext ctx, int index) {
        java.lang.reflect.Type declared = ctx.declaredType();
        return declared instanceof java.lang.reflect.ParameterizedType
                ? ((java.lang.reflect.ParameterizedType) declared).getActualTypeArguments()[index] : Object.class;
    }

    /** A module's converter for {@code Optional}, binding its content as the declared type argument (top-up T1). */
    @ConfigConverterFor(java.util.Optional.class)
    public static class OptionalConverter implements ConfigConverter<java.util.Optional<?>> {
        @Override
        public Object toPlain(java.util.Optional<?> value, ConversionContext ctx) throws ConversionException {
            return value.isPresent() ? ctx.toPlain(value.get()) : null;
        }

        @Override
        public java.util.Optional<?> fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
            return java.util.Optional.ofNullable(ctx.fromPlain(plain, contentType(ctx, 0)));
        }
    }

    /** A module's converter for {@code AtomicReference}, binding its content as the declared type argument (top-up T1). */
    @ConfigConverterFor(java.util.concurrent.atomic.AtomicReference.class)
    public static class AtomicReferenceConverter implements ConfigConverter<java.util.concurrent.atomic.AtomicReference<?>> {
        @Override
        public Object toPlain(java.util.concurrent.atomic.AtomicReference<?> value, ConversionContext ctx)
                throws ConversionException {
            return ctx.toPlain(value.get());
        }

        @Override
        public java.util.concurrent.atomic.AtomicReference<?> fromPlain(Object plain, ConversionContext ctx)
                throws ConversionException {
            return new java.util.concurrent.atomic.AtomicReference<>(ctx.fromPlain(plain, contentType(ctx, 0)));
        }
    }

    /** A module's converter for Guava's {@code Multimap}, binding its values as the declared value type (top-up T1). */
    @ConfigConverterFor(com.google.common.collect.Multimap.class)
    public static class MultimapConverter implements ConfigConverter<com.google.common.collect.Multimap<?, ?>> {
        @Override
        public Object toPlain(com.google.common.collect.Multimap<?, ?> value, ConversionContext ctx) {
            return Collections.emptyMap();
        }

        @Override
        public com.google.common.collect.Multimap<?, ?> fromPlain(Object plain, ConversionContext ctx)
                throws ConversionException {
            com.google.common.collect.Multimap<Object, Object> values = com.google.common.collect.ArrayListMultimap.create();
            if (plain instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) plain).entrySet()) {
                    values.put(entry.getKey(), ctx.fromPlain(entry.getValue(), contentType(ctx, 1)));
                }
            }
            return values;
        }
    }

    /** A list subclass with a constrained field of its own, which the generic binder never binds (top-up T1). */
    public static class CappedList extends java.util.ArrayList<Plain> {
        private static final long serialVersionUID = 1L;
        @Range(min = 1, max = 5)
        int cap = 1;
    }

    /** A map subclass with a constrained field of its own (top-up T1). */
    public static class CappedMap extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;
        @NotEmpty
        String label = "x";
    }

    /** A generic holder (top-up T2). */
    public static final class Holder<T> {
        T held;
    }

    /** A value class using one generic holder with nine different type arguments, the ninth constrained (top-up T2). */
    public static final class ManyHolders {
        Holder<String> a;
        Holder<Integer> b;
        Holder<Long> c;
        Holder<Double> d;
        Holder<Boolean> e;
        Holder<Plain> f;
        Holder<java.util.UUID> g;
        Holder<Character> h;
        Holder<Item> i;
        String name;

        ManyHolders(String name) {
            this.name = name;
        }
    }

    /** A recursive generic whose type grows at every level: Tree<T> holds Tree<List<T>> (top-up T2). */
    public static final class Tree<T> {
        T value;
        java.util.List<Tree<java.util.List<T>>> kids;
        String name;

        Tree(String name) {
            this.name = name;
        }
    }

    /** A self-referencing value type: the same type again, nothing new to walk (control for the walk limit). */
    public static final class Chain {
        Chain next;
        String name;

        Chain(String name) {
            this.name = name;
        }
    }

    /** An interface a value type's field is declared as; its module implementation carries a constraint (maintainer 2026-10-06). */
    public interface Shape {
    }

    /** A module implementation of {@link Shape} with a constrained field of its own. */
    public static final class Circle implements Shape {
        @Range(min = 1, max = 10)
        int radius = 1;
    }

    /** A value type holding a {@link Shape}. */
    public static final class Drawing {
        Shape shape;
        String name;

        Drawing(String name) {
            this.name = name;
        }
    }

    /** An interface whose module implementations carry no constraint (control). */
    public interface Marker {
    }

    /** An unconstrained implementation of {@link Marker}. */
    public static final class Flag implements Marker {
        String label;
    }

    /** A value type holding a {@link Marker}. */
    public static final class Tagged {
        Marker marker;
        String name;

        Tagged(String name) {
            this.name = name;
        }
    }

    /** An abstract base a value type's field is declared as; its module subclass carries a constraint. */
    public abstract static class Reward {
        String id;
    }

    /** A module subclass of {@link Reward} with a constrained field of its own. */
    public static final class CoinReward extends Reward {
        @NotEmpty
        String currency;
    }

    /** A value type holding a {@link Reward}. */
    public static final class Prize {
        Reward reward;
        String name;

        Prize(String name) {
            this.name = name;
        }
    }

    /** An abstract base whose module subclasses carry no constraint (control). */
    public abstract static class Effect {
        String id;
    }

    /** An unconstrained subclass of {@link Effect}. */
    public static final class GlowEffect extends Effect {
        int ticks;
    }

    /** A value type holding an {@link Effect}. */
    public static final class Aura {
        Effect effect;
        String name;

        Aura(String name) {
            this.name = name;
        }
    }

    /** A generic interface; its module implementation holds its type argument. */
    public interface Slot<T> {
    }

    /** A generic module implementation of {@link Slot}: its content is the slot's type argument. */
    public static final class ContentSlot<T> implements Slot<T> {
        T content;
    }

    /** A value type holding a {@code Slot<Item>}: the implementation's content is an Item, whose fields are constrained. */
    public static final class ItemRack {
        Slot<Item> slot;
        String name;

        ItemRack(String name) {
            this.name = name;
        }
    }

    /** A value type holding a {@code Slot<Plain>}: nothing constrained (control). */
    public static final class PlainRack {
        Slot<Plain> slot;
        String name;

        PlainRack(String name) {
            this.name = name;
        }
    }

    /** Binds {@link Drawing}. */
    @ConfigConverterFor(Drawing.class)
    public static class DrawingConverter extends NamedConverter<Drawing> {
        @Override Drawing named(String name) { return new Drawing(name); }
        @Override String name(Drawing value) { return value.name; }
    }

    /** Binds {@link Tagged}. */
    @ConfigConverterFor(Tagged.class)
    public static class TaggedConverter extends NamedConverter<Tagged> {
        @Override Tagged named(String name) { return new Tagged(name); }
        @Override String name(Tagged value) { return value.name; }
    }

    /** Binds {@link Prize}. */
    @ConfigConverterFor(Prize.class)
    public static class PrizeConverter extends NamedConverter<Prize> {
        @Override Prize named(String name) { return new Prize(name); }
        @Override String name(Prize value) { return value.name; }
    }

    /** Binds {@link Aura}. */
    @ConfigConverterFor(Aura.class)
    public static class AuraConverter extends NamedConverter<Aura> {
        @Override Aura named(String name) { return new Aura(name); }
        @Override String name(Aura value) { return value.name; }
    }

    /** Binds {@link ItemRack}. */
    @ConfigConverterFor(ItemRack.class)
    public static class ItemRackConverter extends NamedConverter<ItemRack> {
        @Override ItemRack named(String name) { return new ItemRack(name); }
        @Override String name(ItemRack value) { return value.name; }
    }

    /** Binds {@link PlainRack}. */
    @ConfigConverterFor(PlainRack.class)
    public static class PlainRackConverter extends NamedConverter<PlainRack> {
        @Override PlainRack named(String name) { return new PlainRack(name); }
        @Override String name(PlainRack value) { return value.name; }
    }

    /** Binds {@link ManyHolders}. */
    @ConfigConverterFor(ManyHolders.class)
    public static class ManyHoldersConverter extends NamedConverter<ManyHolders> {
        @Override ManyHolders named(String name) { return new ManyHolders(name); }
        @Override String name(ManyHolders value) { return value.name; }
    }

    /** Binds {@link Tree}. */
    @ConfigConverterFor(Tree.class)
    public static class TreeConverter extends NamedConverter<Tree<?>> {
        @Override Tree<?> named(String name) { return new Tree<>(name); }
        @Override String name(Tree<?> value) { return value.name; }
    }

    /** Binds {@link Chain}. */
    @ConfigConverterFor(Chain.class)
    public static class ChainConverter extends NamedConverter<Chain> {
        @Override Chain named(String name) { return new Chain(name); }
        @Override String name(Chain value) { return value.name; }
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
                                        TransientLinkConverter.class, OptionalConverter.class,
                                        AtomicReferenceConverter.class, MultimapConverter.class,
                                        ManyHoldersConverter.class, TreeConverter.class, ChainConverter.class,
                                        DrawingConverter.class, TaggedConverter.class, PrizeConverter.class,
                                        AuraConverter.class, ItemRackConverter.class, PlainRackConverter.class))
                                : Collections.emptySet();
                    });
            ConverterRegistry.prepareModule(plugin, new String[]{"fixture.constraints"},
                    ConstraintFixtures.class.getClassLoader());
        }
    }
}
