package com.ultikits.ultitools.abstracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.DelegateDeserialization;
import com.ultikits.ultitools.annotations.ConfigEntry;

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
        }

        Fixture() {
            this("config/alpha-writer.yml");
        }
    }
}
