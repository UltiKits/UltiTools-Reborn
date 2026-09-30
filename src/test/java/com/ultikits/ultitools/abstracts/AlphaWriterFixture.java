package com.ultikits.ultitools.abstracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.DelegateDeserialization;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;

/** The fixture set the alpha-writer equivalence test saves (shared by the probe run on alpha). */
final class AlphaWriterFixture {
    private AlphaWriterFixture() {
    }

    /** A registered Bukkit-serializable parent, like Paper's ItemStack. */
    public static class ParentCs implements ConfigurationSerializable {
        int x = 1;

        public static ParentCs deserialize(Map<String, Object> map) {
            ParentCs value = new ParentCs();
            value.x = ((Number) map.get("x")).intValue();
            return value;
        }

        @Override
        public Map<String, Object> serialize() {
            return Collections.<String, Object>singletonMap("x", x);
        }
    }

    /** A subclass whose registered alias is the parent's, like Paper's CraftItemStack. */
    @DelegateDeserialization(ParentCs.class)
    public static class ChildCs extends ParentCs {
        int extra = 2;
    }

    /** A module's own parser for a Set field: writes the set as one comma-joined value in a section. */
    public static class JoinedSetParser extends ConfigParser<Set<String>> {
        @Override
        public Set<String> parse(Object object) {
            Set<String> result = new LinkedHashSet<>();
            if (object instanceof ConfigurationSection) {
                for (String part : ((ConfigurationSection) object).getString("joined", "").split(",")) {
                    if (!part.isEmpty()) {
                        result.add(part);
                    }
                }
            }
            return result;
        }

        @Override
        public MemorySection serializeToMemorySection(Set<String> object) {
            MemoryConfiguration section = new MemoryConfiguration();
            section.set("joined", String.join(",", object));
            return section;
        }
    }

    @SuppressWarnings("unused")
    static class Fixture extends AbstractConfigEntity {
        @ConfigEntry(path = "flat")
        Map<String, String> flat = new LinkedHashMap<>();

        @ConfigEntry(path = "nested")
        Map<String, Map<String, Object>> nested = new LinkedHashMap<>();

        @ConfigEntry(path = "rewards")
        List<Map<String, String>> rewards = new ArrayList<>();

        @ConfigEntry(path = "items")
        List<ParentCs> items = new ArrayList<>();

        @ConfigEntry(path = "kits")
        Map<String, ParentCs> kits = new LinkedHashMap<>();

        @ConfigEntry(path = "tiers")
        List<Map<Integer, String>> tiers = new ArrayList<>();

        @ConfigEntry(path = "tags", parser = JoinedSetParser.class)
        Set<String> tags = new LinkedHashSet<>();

        Fixture(String configFilePath) {
            super(configFilePath);
            flat.put("plain", "1");
            flat.put("my.rule", "x");
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("x.y", 1);
            inner.put("k", "v");
            nested.put("r", inner);
            Map<String, String> reward = new LinkedHashMap<>();
            reward.put("minecraft.diamond", "5");
            reward.put("stick", "1");
            rewards.add(reward);
            items.add(new ChildCs());
            kits.put("starter", new ChildCs());
            Map<Integer, String> tier = new LinkedHashMap<>();
            tier.put(1, "a");
            tiers.add(tier);
            tags.add("red");
        }

        Fixture() {
            this("config/alpha-writer.yml");
        }
    }
}
