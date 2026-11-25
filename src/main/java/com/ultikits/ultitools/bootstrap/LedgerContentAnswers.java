package com.ultikits.ultitools.bootstrap;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.Nullable;

import com.ultikits.ultitools.utils.ModuleContent;
import com.ultikits.ultitools.utils.ModuleContentKind;

/** Immutable, precomputed lookup so pending changes can query under their lock without disk I/O. */
final class LedgerContentAnswers {
    private static final ModuleContent UNKNOWN = ModuleContent.unknown();
    private static final List<String> PRIORITY = List.of(ModuleContentKind.ENCHANTMENT, ModuleContentKind.DATAPACK,
            ModuleContentKind.TAG, ModuleContentKind.UNKNOWN);
    private static final Comparator<String> CANONICAL = Comparator.comparingInt(LedgerContentAnswers::priority)
            .thenComparing(Comparator.naturalOrder());

    private final Map<String, ModuleContent> exact;
    private final Map<String, ModuleContent> fallback;
    private final ModuleContent unrecorded;

    private LedgerContentAnswers(Map<String, ModuleContent> exact, Map<String, ModuleContent> fallback, boolean unreadable) {
        this.exact = Map.copyOf(exact);
        this.fallback = Collections.unmodifiableMap(fallback);
        unrecorded = unreadable ? UNKNOWN : ModuleContent.NONE;
    }

    static LedgerContentAnswers build(Map<String, ContributionLedger.Row> rows, Set<String> successful,
                                      boolean unreadable, Logger logger) {
        Map<String, ModuleContent> exact = new HashMap<>();
        Map<String, ModuleContent> fallback = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        rows.forEach((module, row) -> {
            ModuleContent content = answer(module, row, logger);
            exact.put(module, content);
            fallback.putIfAbsent(module, content);
        });
        successful.forEach(module -> {
            exact.putIfAbsent(module, ModuleContent.NONE);
            fallback.putIfAbsent(module, ModuleContent.NONE);
        });
        return new LedgerContentAnswers(exact, fallback, unreadable);
    }

    ModuleContent contentOf(@Nullable String identifyString, String moduleName) {
        ModuleContent answer = exact.get(Objects.requireNonNull(moduleName, "moduleName"));
        if (answer != null) {
            return answer;
        }
        return identifyString == null ? unrecorded : fallback.getOrDefault(identifyString, unrecorded);
    }

    private static ModuleContent answer(String module, ContributionLedger.Row row, Logger logger) {
        try {
            Map<String, Set<String>> keys = new HashMap<>();
            for (ContentEntry entry : row.entries()) {
                keys.computeIfAbsent(ContentCounts.kind(entry), unused -> new HashSet<>()).add(entry.key());
            }
            Map<String, Integer> counts = new HashMap<>();
            keys.forEach((kind, entries) -> counts.put(kind, entries.size()));
            return bounded(module, counts, logger);
        } catch (RuntimeException failure) {
            // This is visited once per module at startup; the many later queries only read the answer.
            ModuleBootstraps.log(logger, Level.WARNING, "[UltiTools] Could not summarize custom content for module '"
                    + module + "': " + ModuleBootstraps.describe(failure), failure);
            return UNKNOWN;
        }
    }

    static ModuleContent bounded(String module, Map<String, Integer> counts, Logger logger) {
        List<String> kinds = counts.entrySet().stream().filter(entry -> entry.getValue() != null && entry.getValue() >= 1)
                .map(Map.Entry::getKey).filter(kind -> ModuleContentKind.PATTERN.matcher(kind).matches())
                .sorted(CANONICAL).toList();
        if (kinds.size() > ModuleContent.MAX_KINDS) {
            ModuleBootstraps.log(logger, Level.WARNING, "[UltiTools] Module '" + module + "' declared " + kinds.size()
                    + " custom content kinds; reporting only the first " + ModuleContent.MAX_KINDS + " in canonical order.", null);
        }
        if (kinds.isEmpty()) {
            return ModuleContent.NONE;
        }
        return new ModuleContent(kinds.stream().limit(ModuleContent.MAX_KINDS)
                .map(kind -> new ModuleContentKind(kind, counts.get(kind))).toList());
    }

    private static int priority(String kind) {
        int index = PRIORITY.indexOf(kind);
        return index < 0 ? PRIORITY.size() : index;
    }
}
