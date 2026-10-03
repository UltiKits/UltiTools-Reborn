package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.exceptions.ConfigurationException;

class ConverterRegistryLookupTest {

    @Test
    void exactClassPrecedesSuperclassAndInterfaces() {
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        MarkerConverter<Root> root = new MarkerConverter<>();
        MarkerConverter<Marker> face = new MarkerConverter<>();
        MarkerConverter<Leaf> leaf = new MarkerConverter<>();
        registry.register(Root.class, root, false);
        registry.register(Marker.class, face, false);
        registry.register(Leaf.class, leaf, false);
        assertThat(registry.resolve(Leaf.class)).isSameAs(leaf);
        assertThat(registry.resolve(Middle.class)).isSameAs(root);
    }

    @Test
    void superclassPrecedesInterfaceAndExactDoesNotServeSubclass() {
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        MarkerConverter<Root> root = new MarkerConverter<>();
        MarkerConverter<Marker> face = new MarkerConverter<>();
        registry.register(Root.class, root, true);
        registry.register(Marker.class, face, false);
        assertThat(registry.resolve(Root.class)).isSameAs(root);
        assertThat(registry.resolve(Leaf.class)).isSameAs(face);
        ConverterRegistry inherited = new ConverterRegistry(ConverterRegistry.framework());
        inherited.register(Root.class, root, false);
        inherited.register(Marker.class, face, false);
        assertThat(inherited.resolve(Leaf.class)).isSameAs(root);
    }

    @Test
    void childHierarchyPrecedesFrameworkExactRegistration() {
        ConverterRegistry parent = new ConverterRegistry(null);
        MarkerConverter<Leaf> frameworkLeaf = new MarkerConverter<>();
        parent.register(Leaf.class, frameworkLeaf, false);
        ConverterRegistry child = new ConverterRegistry(parent);
        MarkerConverter<Root> moduleRoot = new MarkerConverter<>();
        child.register(Root.class, moduleRoot, false);
        assertThat(child.resolve(Leaf.class)).isSameAs(moduleRoot);
    }

    @Test
    void moduleShadowsFrameworkAndDuplicateNamesBothConverters() {
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        MarkerConverter<String> converter = new MarkerConverter<>();
        registry.register(String.class, converter, false);
        assertThat(registry.resolve(String.class)).isSameAs(converter);
        assertThatThrownBy(() -> registry.register(String.class, new OtherConverter(), true))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(MarkerConverter.class.getName())
                .hasMessageContaining(OtherConverter.class.getName());
    }

    @Test
    void recursiveDispatchUsesSameRegistryAndContextIsImmutable() throws Exception {
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        registry.register(Leaf.class, new ConfigConverter<Leaf>() {
            @Override
            public Object toPlain(Leaf value, ConversionContext ctx) throws ConversionException {
                assertThat(ctx.file()).isEqualTo("config/test.yml");
                assertThat(ctx.path()).containsExactly("items", "o.O");
                assertThatThrownBy(() -> ctx.path().add("changed"))
                        .isInstanceOf(UnsupportedOperationException.class);
                return ctx.toPlain("leaf");
            }

            @Override
            public Leaf fromPlain(Object plain, ConversionContext ctx) throws ConversionException {
                assertThat(ctx.<String>fromPlain(plain, String.class)).isEqualTo("leaf");
                return new Leaf();
            }
        }, false);
        assertThat(registry.toPlain(new Leaf(), Leaf.class, "config/test.yml", Arrays.asList("items", "o.O")))
                .isEqualTo("leaf");
        assertThat(registry.<Leaf>fromPlain("leaf", Leaf.class, "config/test.yml", Collections.singletonList("items")))
                .isInstanceOf(Leaf.class);
    }

    @Test
    void nonPlainCustomOutputIsRefusedAtRegistryBoundary() {
        ConverterRegistry registry = new ConverterRegistry(ConverterRegistry.framework());
        registry.register(Leaf.class, new MarkerConverter<Leaf>(), false);
        assertThatThrownBy(() -> registry.toPlain(new Leaf(), Leaf.class, "config/test.yml",
                Collections.singletonList("item")))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("config/test.yml")
                .hasMessageContaining("item")
                .hasMessageContaining(Leaf.class.getName());
    }

    interface Marker { }
    static class Root { }
    static class Middle extends Root implements Marker { }
    static class Leaf extends Middle { }

    public static class MarkerConverter<T> implements ConfigConverter<T> {
        @Override
        public Object toPlain(T value, ConversionContext ctx) {
            return value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T fromPlain(Object plain, ConversionContext ctx) {
            return (T) plain;
        }
    }

    public static class OtherConverter extends MarkerConverter<String> { }
}
