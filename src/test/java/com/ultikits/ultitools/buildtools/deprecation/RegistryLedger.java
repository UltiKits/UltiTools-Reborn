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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The cumulative deprecation ledger (D-07): entries only ever enter. A member gone from source
 * flips to {@code REMOVED} only when the japicmp report independently confirms it (D-22) - never
 * on the evidence of a single source.
 */
public final class RegistryLedger {

    /** Keyed by {@link RegistryKey#toString()} so lookups don't depend on object identity. */
    /**
     * Matches a javadoc {@code @code} inline tag so {@link #toMarkdown()} can render it as a
     * Markdown code span. Declared here, at the top of the class, deliberately: PMD's
     * FieldDeclarationsShouldBeAtStartOfClass fires on a constant introduced further down.
     */
    private static final Pattern JAVADOC_CODE_TAG = Pattern.compile("\\{@code\\s+([^}]*)\\}");

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
     * {@code changeStatus="REMOVED"} (D-22), recording a newly-{@code REMOVED} entry's
     * {@code removedIn} as {@code currentVersion} -- the version actually being built right now,
     * when japicmp and the source scan agree the member is gone -- rather than the member's own
     * (possibly stale, possibly still-in-the-future) {@code removeIn} schedule (#377, WR-05,
     * 16-REVIEW-cloud.md). An entry may transition to {@code REMOVED} only when BOTH the source
     * scan no longer finds it AND japicmp independently confirms the removal; either source
     * disagreeing alone with the other is fatal (D-22) - see {@link LedgerMergeConflictException}.
     * <p>
     * <b>Why this matters:</b> {@code removeIn} and the actual removal version happen to coincide
     * for the ordinary cross-release case (deprecated in one released version, removed while
     * building the very next one), so recording either value there produced the same answer. They
     * diverge whenever a member is retained past its scheduled removal (#377's "later release"
     * case), removed in the very release it was scheduled for (the coinciding case that must keep
     * working), or removed with no schedule at all -- {@code removeIn == null} -- where the removal
     * version must still be published rather than left absent. {@code currentVersion} is the value
     * that is actually true in every one of those cases; the entry's own {@code removeIn} is left
     * untouched by {@link DeprecationEntry#withRemoved(String)} so the original schedule remains
     * readable alongside the actual removal version on the same entry (both fields are emitted by
     * {@link #toJson()}).
     *
     * @param currentVersion the version being built right now (typically {@code ${project.version}}
     *                       with any {@code -SNAPSHOT} suffix stripped) -- the value every
     *                       newly-{@code REMOVED} entry's {@code removedIn} is set to. Required;
     *                       every caller knows the version it is building against.
     * @throws NullPointerException if {@code currentVersion} is {@code null}
     */
    // PMD.NPathComplexity: 216 against a 200 threshold. The method is one pass over each of
    // three inputs with a flat guard per entry -- no guard nests inside another, and the count
    // is the product of independent per-entry checks rather than a measure of reachable state.
    @SuppressWarnings("PMD.NPathComplexity")
    public static RegistryLedger merge(RegistryLedger prior, List<DeprecationEntry> freshScan,
            Set<RegistryKey> japicmpRemovedKeys, String currentVersion) {
        Objects.requireNonNull(currentVersion, "currentVersion");
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
                // #377/WR-05: the version this removal is recorded under is the version being
                // built NOW (currentVersion), not the entry's own, possibly-stale or absent
                // removeIn -- see this method's javadoc. entry.getRemoveIn() is left unchanged by
                // withRemoved(), so the original schedule stays readable on the same entry.
                merged.put(keyString, entry.withRemoved(currentVersion));
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
                        .append(javadocToMarkdown(nullToDash(entry.getReplacement())))
                        .append(" |\n");
            }
        } else {
            sb.append("| Member | Since | Scheduled removal | Replacement |\n");
            sb.append("|---|---|---|---|\n");
            for (DeprecationEntry entry : matching) {
                sb.append("| `").append(entry.getKey()).append("` | ")
                        .append(nullToDash(entry.getSince())).append(" | ")
                        .append(nullToDash(entry.getRemoveIn())).append(" | ")
                        .append(javadocToMarkdown(nullToDash(entry.getReplacement())))
                        .append(" |\n");
            }
        }
        sb.append('\n');
    }

    private static String nullToDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    /**
     * Renders javadoc inline tags in the Replacement column as Markdown. That column is the one
     * field carrying prose copied verbatim out of a {@code @deprecated} javadoc block, and
     * {@code @code} is the only inline tag that occurs in practice.
     * <p>
     * Not cosmetic. A javadoc placeholder reaches the table as a literal angle-bracketed token,
     * which Markdown parses as inline HTML -- markdownlint MD033, and a renderer may swallow the
     * token outright, so the reader is shown a path with the placeholder missing. Inside a code
     * span it is both lint-clean and visible.
     */
    private static String javadocToMarkdown(String value) {
        return value == null ? null : JAVADOC_CODE_TAG.matcher(value).replaceAll("`$1`");
    }
}
