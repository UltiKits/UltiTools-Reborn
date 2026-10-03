package com.ultikits.ultitools.abstracts.golden;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

/** Generated once from the fourteen bundled corpus roots; one Object entry per literal top-level key. */
public final class BundledEntities {
    private BundledEntities() { }

    /** bundled/UltiChat/config/announcements.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture1 extends AbstractConfigEntity {
        @ConfigEntry(path = "announcements") public Object entry1;
        public Fixture1(String path) { super(path); }
    }

    /** bundled/UltiChat/config/autoreply.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture2 extends AbstractConfigEntity {
        @ConfigEntry(path = "autoreply") public Object entry1;
        public Fixture2(String path) { super(path); }
    }

    /** bundled/UltiChat/config/channels.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture3 extends AbstractConfigEntity {
        @ConfigEntry(path = "channels") public Object entry1;
        public Fixture3(String path) { super(path); }
    }

    /** bundled/UltiChat/config/chat.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture4 extends AbstractConfigEntity {
        @ConfigEntry(path = "chat") public Object entry1;
        @ConfigEntry(path = "join-quit") public Object entry2;
        @ConfigEntry(path = "mentions") public Object entry3;
        @ConfigEntry(path = "anti-spam") public Object entry4;
        public Fixture4(String path) { super(path); }
    }

    /** bundled/UltiChat/config/emojis.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture5 extends AbstractConfigEntity {
        @ConfigEntry(path = "emojis") public Object entry1;
        public Fixture5(String path) { super(path); }
    }

    /** bundled/UltiEconomy/config/config.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture6 extends AbstractConfigEntity {
        @ConfigEntry(path = "initial-cash") public Object entry1;
        @ConfigEntry(path = "currency-name") public Object entry2;
        @ConfigEntry(path = "currency-symbol") public Object entry3;
        @ConfigEntry(path = "bank") public Object entry4;
        @ConfigEntry(path = "interest") public Object entry5;
        @ConfigEntry(path = "leaderboard") public Object entry6;
        public Fixture6(String path) { super(path); }
    }

    /** bundled/UltiEconomy/config/currencies.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture7 extends AbstractConfigEntity {
        @ConfigEntry(path = "currencies") public Object entry1;
        public Fixture7(String path) { super(path); }
    }

    /** bundled/UltiKits/config/config.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture8 extends AbstractConfigEntity {
        @ConfigEntry(path = "enabled") public Object entry1;
        @ConfigEntry(path = "click_cooldown_ms") public Object entry2;
        @ConfigEntry(path = "kits_per_page") public Object entry3;
        public Fixture8(String path) { super(path); }
    }

    /** bundled/UltiKits/kits/en/starter.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture9 extends AbstractConfigEntity {
        @ConfigEntry(path = "displayName") public Object entry1;
        @ConfigEntry(path = "description") public Object entry2;
        @ConfigEntry(path = "icon") public Object entry3;
        @ConfigEntry(path = "price") public Object entry4;
        @ConfigEntry(path = "levelRequired") public Object entry5;
        @ConfigEntry(path = "permission") public Object entry6;
        @ConfigEntry(path = "reBuyable") public Object entry7;
        @ConfigEntry(path = "cooldown") public Object entry8;
        @ConfigEntry(path = "playerCommands") public Object entry9;
        @ConfigEntry(path = "consoleCommands") public Object entry10;
        @ConfigEntry(path = "items") public Object entry11;
        public Fixture9(String path) { super(path); }
    }

    /** bundled/UltiKits/kits/zh/starter.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture10 extends AbstractConfigEntity {
        @ConfigEntry(path = "displayName") public Object entry1;
        @ConfigEntry(path = "description") public Object entry2;
        @ConfigEntry(path = "icon") public Object entry3;
        @ConfigEntry(path = "price") public Object entry4;
        @ConfigEntry(path = "levelRequired") public Object entry5;
        @ConfigEntry(path = "permission") public Object entry6;
        @ConfigEntry(path = "reBuyable") public Object entry7;
        @ConfigEntry(path = "cooldown") public Object entry8;
        @ConfigEntry(path = "playerCommands") public Object entry9;
        @ConfigEntry(path = "consoleCommands") public Object entry10;
        @ConfigEntry(path = "items") public Object entry11;
        public Fixture10(String path) { super(path); }
    }

    /** bundled/UltiMenu/config/config.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture11 extends AbstractConfigEntity {
        @ConfigEntry(path = "click_cooldown_ms") public Object entry1;
        public Fixture11(String path) { super(path); }
    }

    /** bundled/UltiMenu/menus/en/example.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture12 extends AbstractConfigEntity {
        @ConfigEntry(path = "size") public Object entry1;
        @ConfigEntry(path = "title") public Object entry2;
        @ConfigEntry(path = "permission") public Object entry3;
        @ConfigEntry(path = "bind-item") public Object entry4;
        @ConfigEntry(path = "bind-name") public Object entry5;
        @ConfigEntry(path = "bind-lore") public Object entry6;
        @ConfigEntry(path = "buttons") public Object entry7;
        public Fixture12(String path) { super(path); }
    }

    /** bundled/UltiMenu/menus/zh/example.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture13 extends AbstractConfigEntity {
        @ConfigEntry(path = "size") public Object entry1;
        @ConfigEntry(path = "title") public Object entry2;
        @ConfigEntry(path = "permission") public Object entry3;
        @ConfigEntry(path = "bind-item") public Object entry4;
        @ConfigEntry(path = "bind-name") public Object entry5;
        @ConfigEntry(path = "bind-lore") public Object entry6;
        @ConfigEntry(path = "buttons") public Object entry7;
        public Fixture13(String path) { super(path); }
    }

    /** bundled/UltiWorlds/config/worlds.yml */
    @ConfigEntity("golden.yml")
    public static class Fixture14 extends AbstractConfigEntity {
        @ConfigEntry(path = "default_world") public Object entry1;
        @ConfigEntry(path = "protected_worlds") public Object entry2;
        @ConfigEntry(path = "load_worlds_on_start") public Object entry3;
        @ConfigEntry(path = "auto_unload") public Object entry4;
        @ConfigEntry(path = "tp_to_world") public Object entry5;
        @ConfigEntry(path = "world_spawn") public Object entry6;
        @ConfigEntry(path = "world_isolation") public Object entry7;
        public Fixture14(String path) { super(path); }
    }

    /** Resolves the committed fixture class without deriving the declarations from the reader under test. */
    public static AbstractConfigEntity create(String fixture, String path) {
        switch (fixture) {
            case "bundled/UltiChat/config/announcements.yml": return new Fixture1(path);
            case "bundled/UltiChat/config/autoreply.yml": return new Fixture2(path);
            case "bundled/UltiChat/config/channels.yml": return new Fixture3(path);
            case "bundled/UltiChat/config/chat.yml": return new Fixture4(path);
            case "bundled/UltiChat/config/emojis.yml": return new Fixture5(path);
            case "bundled/UltiEconomy/config/config.yml": return new Fixture6(path);
            case "bundled/UltiEconomy/config/currencies.yml": return new Fixture7(path);
            case "bundled/UltiKits/config/config.yml": return new Fixture8(path);
            case "bundled/UltiKits/kits/en/starter.yml": return new Fixture9(path);
            case "bundled/UltiKits/kits/zh/starter.yml": return new Fixture10(path);
            case "bundled/UltiMenu/config/config.yml": return new Fixture11(path);
            case "bundled/UltiMenu/menus/en/example.yml": return new Fixture12(path);
            case "bundled/UltiMenu/menus/zh/example.yml": return new Fixture13(path);
            case "bundled/UltiWorlds/config/worlds.yml": return new Fixture14(path);
            default: throw new IllegalArgumentException("Unknown bundled fixture: " + fixture);
        }
    }
}
