package com.ultikits.ultitools.buildtools.deprecation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build-time entry point (bound to the {@code verify} phase, after japicmp - see {@code pom.xml})
 * that regenerates {@code compatibility/deprecations.json} and {@code compatibility/DEPRECATIONS.md}
 * from source plus the japicmp report (D-08, D-17, D-22).
 *
 * <p>Reads the prior ledger (if any), scans {@code src/main/java} with
 * {@link JavadocDeprecationScanner}, reads {@code target/japicmp/japicmp.xml} for the set of keys
 * japicmp reports {@code changeStatus="REMOVED"}, merges via {@link RegistryLedger#merge}, and
 * writes both output files. A merge disagreement ({@link LedgerMergeConflictException}) or any
 * other failure exits non-zero, failing {@code mvn verify}.
 */
public final class DeprecationRegistryGenerator {

    private static final Path SRC_ROOT = Paths.get("src/main/java");
    private static final Path JAPICMP_REPORT = Paths.get("target/japicmp/japicmp.xml");
    private static final Path LEDGER_JSON = Paths.get("compatibility/deprecations.json");
    private static final Path LEDGER_MARKDOWN = Paths.get("compatibility/DEPRECATIONS.md");

    private DeprecationRegistryGenerator() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (LedgerMergeConflictException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("DeprecationRegistryGenerator failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws IOException, ReflectiveOperationException {
        RegistryLedger prior = loadPriorLedger();

        JavadocDeprecationScanner scanner = new JavadocDeprecationScanner(
                Thread.currentThread().getContextClassLoader());
        List<DeprecationEntry> freshScan = scanner.scan(SRC_ROOT);

        Set<RegistryKey> japicmpRemoved = readJapicmpRemovedKeys();

        RegistryLedger merged = RegistryLedger.merge(prior, freshScan, japicmpRemoved);

        Files.createDirectories(LEDGER_JSON.getParent());
        Files.write(LEDGER_JSON, merged.toJson().getBytes(StandardCharsets.UTF_8));
        Files.write(LEDGER_MARKDOWN, merged.toMarkdown().getBytes(StandardCharsets.UTF_8));

        System.out.println("DeprecationRegistryGenerator: wrote " + merged.size() + " entries to "
                + LEDGER_JSON + " and " + LEDGER_MARKDOWN);
    }

    private static RegistryLedger loadPriorLedger() throws IOException {
        if (!Files.exists(LEDGER_JSON)) {
            return RegistryLedger.empty();
        }
        String json = new String(Files.readAllBytes(LEDGER_JSON), StandardCharsets.UTF_8);
        if (json.trim().isEmpty()) {
            return RegistryLedger.empty();
        }
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        List<DeprecationEntry> entries = new ArrayList<>();
        for (JsonElement element : array) {
            entries.add(entryFromJson(element.getAsJsonObject()));
        }
        return RegistryLedger.of(entries);
    }

    private static DeprecationEntry entryFromJson(JsonObject obj) {
        String className = obj.get("className").getAsString();
        JsonElement memberNameElement = obj.get("memberName");
        String kindText = obj.get("kind").getAsString();
        DeprecationEntry.Kind kind = DeprecationEntry.Kind.valueOf(kindText);

        RegistryKey key;
        if (kind == DeprecationEntry.Kind.CLASS) {
            key = RegistryKey.forClass(className);
        } else if (kind == DeprecationEntry.Kind.FIELD) {
            key = RegistryKey.forField(className, memberNameElement.getAsString());
        } else {
            key = parseMemberKeyWithParams(obj.get("key").getAsString());
        }

        return DeprecationEntry.builder()
                .key(key)
                .kind(kind)
                .since(nullableString(obj, "since"))
                .forRemoval(obj.get("forRemoval").getAsBoolean())
                .removeIn(nullableString(obj, "removeIn"))
                .replacement(nullableString(obj, "replacement"))
                .status(DeprecationEntry.Status.valueOf(obj.get("status").getAsString()))
                .removedIn(nullableString(obj, "removedIn"))
                .build();
    }

    private static String nullableString(JsonObject obj, String field) {
        JsonElement el = obj.get(field);
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    private static final Pattern MEMBER_KEY = Pattern.compile("^(.+)#([^(]+)\\((.*)\\)$");

    private static RegistryKey parseMemberKeyWithParams(String keyString) {
        Matcher m = MEMBER_KEY.matcher(keyString);
        if (!m.matches()) {
            throw new IllegalStateException("Cannot parse persisted registry key: " + keyString);
        }
        String className = m.group(1);
        String memberName = m.group(2);
        String paramsText = m.group(3);
        List<String> params = paramsText.isEmpty()
                ? java.util.Collections.emptyList()
                : java.util.Arrays.asList(paramsText.split(","));
        return RegistryKey.forMember(className, memberName, params);
    }

    /**
     * Reads {@code target/japicmp/japicmp.xml} and returns the set of {@link RegistryKey}s that
     * japicmp reports with {@code compatibilityChanges} containing a REMOVED-shaped change
     * (class removed, method/constructor removed, or field removed) - the japicmp-side half of
     * D-22's dual-source cross-check. Reconstructs each key from the report's own
     * {@code <method>}/{@code <field>}/{@code <parameter type=...>} attributes so both sides of
     * the check share the exact same string form (D-01).
     */
    private static Set<RegistryKey> readJapicmpRemovedKeys() throws IOException {
        Set<RegistryKey> removed = new LinkedHashSet<>();
        if (!Files.exists(JAPICMP_REPORT)) {
            // No japicmp report (e.g. `-DskipTests` ran before `verify`'s cmp goal on a partial
            // build). Nothing can be confirmed REMOVED without it - an empty set is the safe,
            // conservative default; D-22 requires agreement, and silence from japicmp never
            // authorizes a REMOVED transition on its own.
            return removed;
        }
        String xml = new String(Files.readAllBytes(JAPICMP_REPORT), StandardCharsets.UTF_8);
        removed.addAll(JapicmpReportParser.findRemovedKeys(xml));
        return removed;
    }
}
