package com.ultikits.ultitools.bootstrap;

import java.util.List;

import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.utils.ModuleContentKind;

/** Counts distinct content keys for the early log and the translated console report. */
record ContentCounts(int enchantments, int tags, int datapacks) {
    static ContentCounts of(List<ContentEntry> entries) {
        return new ContentCounts(count(entries, ModuleContentKind.ENCHANTMENT), count(entries, ModuleContentKind.TAG),
                count(entries, ModuleContentKind.DATAPACK));
    }

    static String kind(ContentEntry entry) {
        return entry.kind().startsWith(ContentEntry.TAG_PREFIX) ? ModuleContentKind.TAG : entry.kind();
    }

    private static int count(List<ContentEntry> entries, String kind) {
        return (int) entries.stream().filter(entry -> kind(entry).equals(kind)).map(ContentEntry::key).distinct().count();
    }

    Arg[] args() {
        return new Arg[]{Arg.number("enchantments", enchantments), Arg.number("tags", tags), Arg.number("datapacks", datapacks)};
    }
}
