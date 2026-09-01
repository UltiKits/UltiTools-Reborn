package com.ultikits.ultitools.buildtools.deprecation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cumulative deprecation ledger (D-07): entries only ever enter. A member gone from source
 * flips to {@code REMOVED} only when the japicmp report independently confirms it (D-22) - never
 * on the evidence of a single source.
 */
public final class RegistryLedger {

    /** Keyed by {@link RegistryKey#toString()} so lookups don't depend on object identity. */
    private final Map<String, DeprecationEntry> entriesByKeyString;

    private RegistryLedger(Map<String, DeprecationEntry> entriesByKeyString) {
        this.entriesByKeyString = entriesByKeyString;
    }

    public static RegistryLedger empty() {
        return new RegistryLedger(new LinkedHashMap<>());
    }

    public static RegistryLedger of(List<DeprecationEntry> entries) {
        Map<String, DeprecationEntry> map = new LinkedHashMap<>();
        for (DeprecationEntry entry : entries) {
            map.put(entry.getKey().toString(), entry);
        }
        return new RegistryLedger(map);
    }

    /**
     * Merges {@code prior} with a fresh source scan and the set of keys japicmp's report marks
     * {@code changeStatus="REMOVED"} (D-22). An entry may transition to {@code REMOVED} only when
     * BOTH the source scan no longer finds it AND japicmp independently confirms the removal;
     * either source disagreeing alone with the other is fatal (D-22) - see
     * {@link LedgerMergeConflictException}.
     */
    public static RegistryLedger merge(RegistryLedger prior, List<DeprecationEntry> freshScan, Set<RegistryKey> japicmpRemovedKeys) {
        Map<String, DeprecationEntry> merged = new LinkedHashMap<>();
        Set<String> freshKeyStrings = new HashSet<>();
        for (DeprecationEntry entry : freshScan) {
            String keyString = entry.getKey().toString();
            freshKeyStrings.add(keyString);
            merged.put(keyString, entry);
        }

        Set<String> japicmpRemovedKeyStrings = new HashSet<>();
        for (RegistryKey key : japicmpRemovedKeys) {
            japicmpRemovedKeyStrings.add(key.toString());
        }

        List<String> conflicts = new ArrayList<>();

        // Mirror case: japicmp reports REMOVED for a key the fresh source scan still finds.
        for (RegistryKey removedKey : japicmpRemovedKeys) {
            if (freshKeyStrings.contains(removedKey.toString())) {
                conflicts.add("japicmp reports REMOVED for " + removedKey
                        + " but the source scan still finds its declaration");
            }
        }

        // Entries carried from the prior ledger but absent from the fresh scan.
        for (Map.Entry<String, DeprecationEntry> priorEntry : prior.entriesByKeyString.entrySet()) {
            String keyString = priorEntry.getKey();
            DeprecationEntry entry = priorEntry.getValue();
            if (freshKeyStrings.contains(keyString)) {
                continue; // still declared in source; the fresh copy already won above
            }
            if (entry.getStatus() == DeprecationEntry.Status.REMOVED) {
                merged.put(keyString, entry); // already-removed entries stay as history
                continue;
            }
            if (japicmpRemovedKeyStrings.contains(keyString)) {
                merged.put(keyString, entry.withRemoved(entry.getRemoveIn()));
            } else {
                conflicts.add("source scan no longer finds " + entry.getKey()
                        + " but japicmp does not report it as REMOVED");
            }
        }

        if (!conflicts.isEmpty()) {
            throw new LedgerMergeConflictException(conflicts);
        }

        return new RegistryLedger(merged);
    }

    /** All entries, in {@link RegistryKey}'s total order (GEN-04 ordering). */
    public List<DeprecationEntry> entries() {
        List<DeprecationEntry> list = new ArrayList<>(entriesByKeyString.values());
        list.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return list;
    }

    public int size() {
        return entriesByKeyString.size();
    }

    /**
     * Serializes this ledger to deterministic, pretty-printed JSON. Entries are sorted by
     * {@link RegistryKey}'s total order and every object's fields are inserted in a fixed order
     * via {@link JsonObject} (a {@code LinkedHashMap}-backed structure), so two runs over
     * unchanged content produce byte-identical output regardless of insertion order (GEN-04).
     */
    public String toJson() {
        JsonArray array = new JsonArray();
        for (DeprecationEntry entry : entries()) {
            array.add(entryToJson(entry));
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(array) + "\n";
    }

    private static JsonObject entryToJson(DeprecationEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", entry.getKey().toString());
        obj.addProperty("className", entry.getKey().getClassName());
        obj.addProperty("memberName", entry.getKey().getMemberName());
        obj.addProperty("kind", entry.getKind().name());
        obj.addProperty("since", entry.getSince());
        obj.addProperty("forRemoval", entry.isForRemoval());
        obj.addProperty("removeIn", entry.getRemoveIn());
        obj.addProperty("replacement", entry.getReplacement());
        obj.addProperty("status", entry.getStatus().name());
        obj.addProperty("removedIn", entry.getRemovedIn());
        return obj;
    }

    /**
     * Renders this ledger as the human-readable Markdown companion (D-17). One table per
     * {@link DeprecationEntry.Status}, entries in {@link RegistryKey}'s total order.
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# UltiTools-API Deprecation Registry\n\n");
        sb.append("Generated by `DeprecationRegistryGenerator` (GEN-12) from the `@Deprecated` ")
                .append("annotations and `@deprecated`/`@removeIn` javadoc tags in `src/main/java`, ")
                .append("cross-checked against the japicmp report. Do not hand-edit - it is ")
                .append("regenerated on every `mvn verify` and any manual change is overwritten.\n\n");

        appendSection(sb, "Removed", DeprecationEntry.Status.REMOVED);
        appendSection(sb, "Announced for removal", DeprecationEntry.Status.ANNOUNCED);
        appendSection(sb, "Deprecated", DeprecationEntry.Status.DEPRECATED);
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String title, DeprecationEntry.Status status) {
        List<DeprecationEntry> matching = new ArrayList<>();
        for (DeprecationEntry entry : entries()) {
            if (entry.getStatus() == status) {
                matching.add(entry);
            }
        }
        sb.append("## ").append(title).append('\n');
        if (matching.isEmpty()) {
            sb.append("\nNone.\n\n");
            return;
        }
        sb.append('\n');
        if (status == DeprecationEntry.Status.REMOVED) {
            sb.append("| Member | Since | Removed in | Replacement |\n");
            sb.append("|---|---|---|---|\n");
            for (DeprecationEntry entry : matching) {
                sb.append("| `").append(entry.getKey()).append("` | ")
                        .append(nullToDash(entry.getSince())).append(" | ")
                        .append(nullToDash(entry.getRemovedIn())).append(" | ")
                        .append(nullToDash(entry.getReplacement())).append(" |\n");
            }
        } else {
            sb.append("| Member | Since | Scheduled removal | Replacement |\n");
            sb.append("|---|---|---|---|\n");
            for (DeprecationEntry entry : matching) {
                sb.append("| `").append(entry.getKey()).append("` | ")
                        .append(nullToDash(entry.getSince())).append(" | ")
                        .append(nullToDash(entry.getRemoveIn())).append(" | ")
                        .append(nullToDash(entry.getReplacement())).append(" |\n");
            }
        }
        sb.append('\n');
    }

    private static String nullToDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
