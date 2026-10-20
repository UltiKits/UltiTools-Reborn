package com.ultikits.ultitools.abstracts.golden;

import java.util.*;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/** Retained 6.2 capture declarations/defaults; only public access and injectable file paths differ. */
public final class Capture62Entities {
    private Capture62Entities() { }
    @org.bukkit.configuration.serialization.SerializableAs("com.ultikits.ultitools.abstracts.Capture62WriterTest$Kit")
    public static class Kit implements ConfigurationSerializable {
        int amount = 3;
        String name = "starter";
        public static Kit deserialize(Map<String, Object> map) { Kit k = new Kit(); k.amount = ((Number) map.get("amount")).intValue(); k.name = (String) map.get("name"); return k; }
        @Override public Map<String, Object> serialize() { Map<String, Object> m = new LinkedHashMap<>(); m.put("amount", amount); m.put("name", name); return m; }
    }

    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Booleans extends AbstractConfigEntity {
        @ConfigEntry(path = "flags.enabled", comment = "Enable the feature") boolean enabled = true;
        @ConfigEntry(path = "flags.debug", comment = "Debug output") boolean debug = false;
        @ConfigEntry(path = "flags.boxed") Boolean boxed = Boolean.TRUE;
        public Booleans(String path) { super(path); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Integers extends AbstractConfigEntity {
        @ConfigEntry(path = "limits.cooldown", comment = "Cooldown in seconds") int cooldown = 30;
        @ConfigEntry(path = "limits.negative") int negative = -5;
        @ConfigEntry(path = "limits.zero") Integer zero = 0;
        @ConfigEntry(path = "limits.big") long big = 10000000000L;
        @ConfigEntry(path = "limits.max-int") int maxInt = Integer.MAX_VALUE;
        public Integers(String path) { super(path); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Decimals extends AbstractConfigEntity {
        @ConfigEntry(path = "economy.rate", comment = "Tax rate") double rate = 0.03;
        @ConfigEntry(path = "economy.multiplier") double multiplier = 1.5;
        @ConfigEntry(path = "economy.whole") double whole = 100.0;
        @ConfigEntry(path = "economy.negative") double negative = -2.25;
        @ConfigEntry(path = "economy.tiny") double tiny = 1.0E-7;
        @ConfigEntry(path = "economy.huge") double huge = 1.0E20;
        public Decimals(String path) { super(path); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Strings extends AbstractConfigEntity {
        @ConfigEntry(path = "chat.format", comment = "Chat format") String format = "&7{player}&f: {message}";
        @ConfigEntry(path = "chat.yes") String yes = "yes";
        @ConfigEntry(path = "chat.colon") String colon = "a: b";
        @ConfigEntry(path = "chat.placeholder") String placeholder = "%player_name%";
        @ConfigEntry(path = "chat.token") String token = "{prefix} hello";
        @ConfigEntry(path = "chat.empty") String empty = "";
        @ConfigEntry(path = "chat.hash") String hash = "#not-a-comment";
        @ConfigEntry(path = "chat.tilde") String tilde = "~";
        @ConfigEntry(path = "chat.null-word") String nullWord = "null";
        @ConfigEntry(path = "chat.number-text") String numberText = "123";
        @ConfigEntry(path = "chat.decimal-text") String decimalText = "1.50";
        @ConfigEntry(path = "chat.date-text") String dateText = "2020-01-01";
        @ConfigEntry(path = "chat.at") String at = "@everyone";
        @ConfigEntry(path = "chat.star") String star = "*star";
        @ConfigEntry(path = "chat.dash") String dash = "- not a list";
        @ConfigEntry(path = "chat.quote") String quote = "it's 'quoted'";
        @ConfigEntry(path = "chat.double-quote") String doubleQuote = "say \"hi\"";
        @ConfigEntry(path = "chat.tab") String tab = "tab\there";
        @ConfigEntry(path = "chat.long") String longText = "&eWelcome to the server, {player}! Please read the rules at /rules before you start building anything here.";
        @ConfigEntry(path = "chat.multi-line") String multiLine = "line one\nline two\nline three";
        @ConfigEntry(path = "chat.trailing-space") String trailingSpace = "trailing ";
        public Strings(String path) { super(path); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Unicode extends AbstractConfigEntity {
        @ConfigEntry(path = "symbols.heart", comment = "Heart symbol") String heart = "❤";
        @ConfigEntry(path = "symbols.chinese") String chinese = "欢迎";
        @ConfigEntry(path = "symbols.emoji") String emoji = new String(Character.toChars(0x1F600));
        @ConfigEntry(path = "symbols.accent") String accent = "café";
        public Unicode(String path) { super(path); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Lists extends AbstractConfigEntity {
        @ConfigEntry(path = "lists.worlds", comment = "Enabled worlds") List<String> worlds = new ArrayList<>(Arrays.asList("world", "world_nether"));
        @ConfigEntry(path = "lists.empty") List<String> empty = new ArrayList<>();
        @ConfigEntry(path = "lists.numbers") List<Integer> numbers = new ArrayList<>(Arrays.asList(1, 2, 3));
        @ConfigEntry(path = "lists.quoted") List<String> quoted = new ArrayList<>(Arrays.asList("yes", "&aGreen", "a: b"));
        public Lists(String path) { super(path); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class ListOfMaps extends AbstractConfigEntity {
        @ConfigEntry(path = "rewards", comment = "Reward table") List<Map<String, Object>> rewards = new ArrayList<>();
        public ListOfMaps(String path) {
            super(path);
            Map<String, Object> a = new LinkedHashMap<>(); a.put("item", "DIAMOND"); a.put("amount", 5); a.put("chance", 0.25);
            Map<String, Object> b = new LinkedHashMap<>(); b.put("item", "minecraft.stick"); b.put("amount", 1);
            rewards.add(a); rewards.add(b);
        }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class NestedMaps extends AbstractConfigEntity {
        @ConfigEntry(path = "channels", comment = "Chat channels") Map<String, Map<String, Object>> channels = new LinkedHashMap<>();
        @ConfigEntry(path = "aliases") Map<String, String> aliases = new LinkedHashMap<>();
        public NestedMaps(String path) {
            super(path);
            Map<String, Object> g = new LinkedHashMap<>(); g.put("prefix", "&7[G]"); g.put("radius", -1); g.put("enabled", true);
            Map<String, Object> l = new LinkedHashMap<>(); l.put("prefix", "&e[L]"); l.put("radius", 100); l.put("enabled", false);
            channels.put("global", g); channels.put("local", l);
            aliases.put("gm", "gamemode"); aliases.put("tp", "teleport");
        }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Dotted extends AbstractConfigEntity {
        @ConfigEntry(path = "emojis.mappings", comment = "Emoji shortcuts") Map<String, String> mappings = new LinkedHashMap<>();
        public Dotted(String path) {
            super(path);
            mappings.put("o.O", "x"); mappings.put("g.m", "y"); mappings.put(":heart:", "❤");
        }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class TrailingDot extends AbstractConfigEntity {
        @ConfigEntry(path = "emojis.mappings") Map<String, String> mappings = new LinkedHashMap<>();
        public TrailingDot(String path) { super(path); mappings.put("wave.", "z"); mappings.put("ok", "y"); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Serializable extends AbstractConfigEntity {
        @ConfigEntry(path = "kits.list", comment = "Kits as a list") List<Kit> list = new ArrayList<>(Collections.singletonList(new Kit()));
        @ConfigEntry(path = "kits.by-name") Map<String, Kit> byName = new LinkedHashMap<>();
        public Serializable(String path) { super(path); byName.put("starter", new Kit()); }
    }
    @com.ultikits.ultitools.annotations.ConfigEntity("golden.yml")
    public static class Comments extends AbstractConfigEntity {
        @ConfigEntry(path = "general.language", comment = "Server language") String language = "en";
        @ConfigEntry(path = "general.motd", comment = "Line one of the comment\nLine two of the comment") String motd = "Hello";
        @ConfigEntry(path = "general.no-comment") int noComment = 1;
        @ConfigEntry(path = "storage.type", comment = "json, sqlite or mysql") String type = "sqlite";
        @ConfigEntry(path = "top-level", comment = "A top-level key") String topLevel = "value";
        public Comments(String path) { super(path); }
    }

}
