package com.ultikits.ultitools.config.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class BukkitConverterTest {
    private ServerMock server;
    private final ConverterRegistry registry = ConverterRegistry.framework();

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        ConfigurationSerialization.registerClass(Holder.class);
        ConfigurationSerialization.registerClass(NullValue.class);
    }
    @AfterEach
    void teardown() {
        ConfigurationSerialization.unregisterClass(Holder.class);
        ConfigurationSerialization.unregisterClass(NullValue.class);
        MockBukkit.unmock();
    }

    static ItemStack item() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD, 2);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Config item");
        meta.setLore(Arrays.asList("first", "second"));
        item.setItemMeta(meta);
        item.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
        return item;
    }

    @Test
    void itemAndDelegateUseParentAliasAndPreserveMetadata() throws Exception {
        ItemStack item = item();
        for (ItemStack input : Arrays.asList(item, new DelegatingItemStackDouble(item))) {
            Object plain = registry.toPlain(input, ItemStack.class, "f", Collections.singletonList("item"));
            assertThat(((Map<?, ?>) plain).get("==")).isEqualTo(ConfigurationSerialization.getAlias(ItemStack.class));
            ItemStack restored = registry.fromPlain(plain, ItemStack.class, "f", Collections.singletonList("item"));
            assertThat(restored.getType()).isEqualTo(input.getType());
            assertThat(restored.getAmount()).isEqualTo(input.getAmount());
            assertThat(restored.getItemMeta().getDisplayName()).isEqualTo("Config item");
            assertThat(restored.getItemMeta().getLore()).containsExactly("first", "second");
            assertThat(restored.getEnchantmentLevel(Enchantment.SHARPNESS)).isEqualTo(2);
        }
    }

    @Test
    void worldLocationAndMaterialRoundTripAndMissingWorldIsLocated() throws Exception {
        World world = server.getWorld("world");
        assertThat(registry.toPlain(world, World.class, "f", Collections.emptyList())).isEqualTo("world");
        assertThat(registry.<World>fromPlain("world", World.class, "f", Collections.emptyList())).isSameAs(world);
        Location input = new Location(world, 1.25, 2, -3.5, 30.5F, -15.25F);
        Object plain = registry.toPlain(input, Location.class, "f", Collections.emptyList());
        assertThat(registry.<Location>fromPlain(plain, Location.class, "f", Collections.emptyList())).isEqualTo(input);
        assertThat(registry.toPlain(Material.STONE, Material.class, "f", Collections.emptyList())).isEqualTo("STONE");
        assertThatThrownBy(() -> registry.fromPlain("missing-world", World.class, "file.yml", Collections.singletonList("world")))
                .isInstanceOf(ConversionException.class).hasMessageContaining("missing-world").hasMessageContaining("file.yml");
    }

    @Test
    void taggedObjectMapsStayPlainAndWrongDeclaredAliasFails() throws Exception {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("==", "ConfigTestHolder"); plain.put("value", "text");
        assertThat(registry.<Object>fromPlain(plain, Object.class, "f", Collections.emptyList())).isEqualTo(plain);
        assertThatThrownBy(() -> registry.fromPlain(plain, Location.class, "f", Collections.singletonList("location")))
                .isInstanceOf(ConversionException.class).hasMessageContaining("Location");
    }

    @Test
    void nestedRegisteredMetadataFactoryReturnsItemMetaNotFactoryClass() throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>(item().getItemMeta().serialize());
        meta.put("==", "ItemMeta");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("==", "ConfigTestHolder"); outer.put("value", meta);
        Holder restored = registry.fromPlain(outer, Holder.class, "f", Collections.singletonList("root"));
        assertThat(restored.value).isInstanceOf(ItemMeta.class);
        assertThat(((ItemMeta) restored.value).getDisplayName()).isEqualTo("Config item");
        assertThat(((ItemMeta) restored.value).getLore()).containsExactly("first", "second");
        ConverterRegistry custom = new ConverterRegistry(registry);
        custom.register(ConfigurationSerialization.getClassByAlias("ItemMeta"), new ConfigConverter<Object>() {
            @Override public Object toPlain(Object value, ConversionContext ctx) { return "custom"; }
            @Override public Object fromPlain(Object plain, ConversionContext ctx) { return "custom-meta"; }
        }, false);
        Holder overridden = custom.fromPlain(outer, Holder.class, "f", Collections.singletonList("root"));
        assertThat(overridden.value).isEqualTo("custom-meta");
    }

    @Test
    void nestedUnknownAndNullAliasesFailAtChildPath() {
        for (String alias : Arrays.asList("unknown-alias", "ConfigTestNull")) {
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("==", "ConfigTestHolder"); outer.put("value", Collections.singletonMap("==", alias));
            assertThatThrownBy(() -> registry.fromPlain(outer, Holder.class, "f", Collections.singletonList("root")))
                    .isInstanceOfSatisfying(ConversionException.class, failure -> {
                        assertThat(failure.path()).containsExactly("root", "value");
                        assertThat(failure.getMessage()).contains(alias);
                    });
        }
    }

    @SerializableAs("ConfigTestHolder")
    public static class Holder implements ConfigurationSerializable {
        final Object value;
        public Holder(Object value) { this.value = value; }
        @Override public Map<String, Object> serialize() { return Collections.singletonMap("value", value); }
        public static Holder deserialize(Map<String, Object> values) { return new Holder(values.get("value")); }
    }
    @SerializableAs("ConfigTestNull")
    public static class NullValue implements ConfigurationSerializable {
        @Override public Map<String, Object> serialize() { return Collections.emptyMap(); }
        public static NullValue deserialize(Map<String, Object> values) { return null; }
    }
}
