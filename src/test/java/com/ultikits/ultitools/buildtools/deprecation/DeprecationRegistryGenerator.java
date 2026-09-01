package com.ultikits.ultitools.buildtools.deprecation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
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
 * from source plus the japicmp report (D-08, D-17, D-22), then runs
 * {@link RemovalConsistencyEvaluator} (D-01/D-21/D-22) over the pom's own {@code <exclude>} list,
 * the japicmp report, and the merged registry.
 *
 * <p>Reads the prior ledger (if any), scans {@code src/main/java} with
 * {@link JavadocDeprecationScanner}, reads {@code target/japicmp/japicmp.xml} for the set of keys
 * japicmp reports {@code changeStatus="REMOVED"}, merges via {@link RegistryLedger#merge}, writes
 * both output files, then evaluates removal-record consistency. A merge disagreement
 * ({@link LedgerMergeConflictException}), a consistency finding, or any other failure exits
 * non-zero, failing {@code mvn verify}.
 */
public final class DeprecationRegistryGenerator {

    private static final Path SRC_ROOT = Paths.get("src/main/java");
    private static final Path JAPICMP_REPORT = Paths.get("target/japicmp/japicmp.xml");
    private static final Path POM_XML = Paths.get("pom.xml");
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

        JapicmpReportReader.Report report = readJapicmpReport();
        Set<RegistryKey> japicmpRemoved = removedKeys(report);
        japicmpRemoved.addAll(impliedRemovedByPrivateVisibility(prior, freshScan, report));

        RegistryLedger merged = RegistryLedger.merge(prior, freshScan, japicmpRemoved);

        Files.createDirectories(LEDGER_JSON.getParent());
        Files.write(LEDGER_JSON, merged.toJson().getBytes(StandardCharsets.UTF_8));
        Files.write(LEDGER_MARKDOWN, merged.toMarkdown().getBytes(StandardCharsets.UTF_8));

        System.out.println("DeprecationRegistryGenerator: wrote " + merged.size() + " entries to "
                + LEDGER_JSON + " and " + LEDGER_MARKDOWN);

        evaluateRemovalConsistency(report, merged);
    }

    /**
     * Runs {@link RemovalConsistencyEvaluator} (D-01/D-21/D-22) and fails the build - naming
     * every offending key - on any finding. This is the japicmp {@code <exclude>} staleness check
     * japicmp itself cannot produce (its {@code ConfigParameters} has no such field).
     */
    private static void evaluateRemovalConsistency(JapicmpReportReader.Report report, RegistryLedger merged)
            throws IOException {
        Set<RegistryKey> excludeKeys = readPomExcludeKeys();
        List<RemovalConsistencyEvaluator.Finding> findings =
                RemovalConsistencyEvaluator.evaluate(excludeKeys, report, merged);
        if (findings.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("RemovalConsistencyEvaluator found ")
                .append(findings.size()).append(" consistency violation(s):\n");
        for (RemovalConsistencyEvaluator.Finding finding : findings) {
            sb.append("  - ").append(finding).append('\n');
        }
        throw new IllegalStateException(sb.toString());
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
     * Reads {@code target/japicmp/japicmp.xml} via {@link JapicmpReportReader} - the full-fidelity
     * parse {@link RemovalConsistencyEvaluator} needs (changeStatus, old-side access modifier,
     * and root scope for every element, not just the REMOVED subset).
     */
    private static JapicmpReportReader.Report readJapicmpReport() throws IOException {
        if (!Files.exists(JAPICMP_REPORT)) {
            // No japicmp report (e.g. `-DskipTests` ran before `verify`'s cmp goal on a partial
            // build). Nothing can be confirmed REMOVED without it - an empty report is the safe,
            // conservative default; D-22 requires agreement, and silence from japicmp never
            // authorizes a REMOVED transition on its own.
            return JapicmpReportReader.Report.empty();
        }
        return JapicmpReportReader.read(JAPICMP_REPORT);
    }

    /**
     * The japicmp-side half of D-22's dual-source cross-check: every key the report shows
     * {@code changeStatus="REMOVED"} - reconstructed from the report's own attributes so both
     * sides of the check share the exact same string form (D-01).
     */
    private static Set<RegistryKey> removedKeys(JapicmpReportReader.Report report) {
        Set<RegistryKey> removed = new LinkedHashSet<>();
        for (java.util.Map.Entry<RegistryKey, JapicmpReportReader.Entry> e : report.entries().entrySet()) {
            if ("REMOVED".equals(e.getValue().getChangeStatus())) {
                removed.add(e.getKey());
            }
        }
        return removed;
    }

    /**
     * The other half of D-22's dual-source rule cannot fire for a {@code private} member: japicmp
     * scans at {@code accessModifier=protected} (pom.xml), so a private field never appears in its
     * report at any {@code changeStatus} - not "unchanged", not "REMOVED", simply absent (confirmed
     * empirically, 07-13-PLAN.md GEN-04: {@code UltiTools#versionWrapper} was a real, deleted,
     * {@code @Deprecated(forRemoval = true)} private field with zero mentions anywhere in
     * {@code target/japicmp/japicmp.xml}). Requiring japicmp's corroboration for such a key would
     * make its {@code REMOVED} transition permanently unreachable through the normal path, even
     * though a private member can, by construction, hold no downstream binary reference - the
     * exact zero-risk criterion {@link RemovalConsistencyEvaluator}'s D-21 already applies to pom
     * {@code <exclude>} entries. This extends that same, already-established principle to the
     * ledger merge itself for prior entries the report has literally never heard of, rather than
     * inventing a new one.
     *
     * <p>Scoped narrowly: only entries the source scan no longer finds (candidates for this run's
     * merge) and that {@code japicmpRemoved} does not already cover, and only where the report has
     * zero record of the key under any {@code changeStatus} - a public/protected member missing
     * from source with no REMOVED report entry is still a hard conflict (D-22's core guarantee is
     * unweakened for anything japicmp could have seen).
     */
    private static Set<RegistryKey> impliedRemovedByPrivateVisibility(
            RegistryLedger prior, List<DeprecationEntry> freshScan, JapicmpReportReader.Report report) {
        Set<String> freshKeyStrings = new java.util.HashSet<>();
        for (DeprecationEntry entry : freshScan) {
            freshKeyStrings.add(entry.getKey().toString());
        }
        Set<RegistryKey> implied = new LinkedHashSet<>();
        for (DeprecationEntry priorEntry : prior.entries()) {
            if (priorEntry.getStatus() == DeprecationEntry.Status.REMOVED) {
                continue; // already history; RegistryLedger.merge carries it forward unconditionally
            }
            if (freshKeyStrings.contains(priorEntry.getKey().toString())) {
                continue; // still declared in source
            }
            if (!report.find(priorEntry.getKey()).isPresent()) {
                implied.add(priorEntry.getKey());
            }
        }
        return implied;
    }

    /**
     * Reads {@code pom.xml}'s japicmp {@code <plugin>} block and returns every
     * {@code <exclude>} entry as a {@link RegistryKey} - the pom-side input
     * {@link RemovalConsistencyEvaluator} cross-checks against the report and the registry.
     * Uses the same JDK parser and XXE hardening as {@link JapicmpReportReader}.
     */
    private static Set<RegistryKey> readPomExcludeKeys() throws IOException {
        String xml = new String(Files.readAllBytes(POM_XML), StandardCharsets.UTF_8);
        Document doc = parsePomXml(xml);

        Element japicmpPlugin = findJapicmpPlugin(doc);
        Set<RegistryKey> keys = new LinkedHashSet<>();
        if (japicmpPlugin == null) {
            return keys;
        }
        NodeList excludeNodes = japicmpPlugin.getElementsByTagName("exclude");
        for (int i = 0; i < excludeNodes.getLength(); i++) {
            String text = excludeNodes.item(i).getTextContent().trim();
            if (!text.isEmpty()) {
                keys.add(parseExcludeKey(text));
            }
        }
        return keys;
    }

    private static Element findJapicmpPlugin(Document doc) {
        NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            NodeList artifactIds = plugin.getElementsByTagName("artifactId");
            for (int j = 0; j < artifactIds.getLength(); j++) {
                if ("japicmp-maven-plugin".equals(artifactIds.item(j).getTextContent().trim())) {
                    return plugin;
                }
            }
        }
        return null;
    }

    /**
     * Parses one japicmp {@code <exclude>} entry's text back into a {@link RegistryKey}, using
     * the same {@code Class#member(paramTypes)} / {@code Class#field} / bare-class syntax japicmp
     * itself requires (confirmed via decompilation, 07-RESEARCH.md "Priority 1").
     */
    private static RegistryKey parseExcludeKey(String text) {
        int hashIndex = text.indexOf('#');
        if (hashIndex < 0) {
            return RegistryKey.forClass(text);
        }
        String className = text.substring(0, hashIndex);
        String rest = text.substring(hashIndex + 1);
        int parenIndex = rest.indexOf('(');
        if (parenIndex < 0) {
            return RegistryKey.forField(className, rest);
        }
        String memberName = rest.substring(0, parenIndex);
        String paramsText = rest.substring(parenIndex + 1, rest.length() - 1).trim();
        List<String> params = paramsText.isEmpty()
                ? java.util.Collections.emptyList()
                : splitParams(paramsText);
        return RegistryKey.forMember(className, memberName, params);
    }

    private static List<String> splitParams(String paramsText) {
        List<String> params = new ArrayList<>();
        for (String part : paramsText.split(",")) {
            params.add(part.trim());
        }
        return params;
    }

    private static Document parsePomXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Same XXE hardening as JapicmpReportReader (T-07-03-04).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse " + POM_XML + ": " + e.getMessage(), e);
        }
    }
}
