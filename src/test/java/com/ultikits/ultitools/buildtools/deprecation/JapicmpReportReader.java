package com.ultikits.ultitools.buildtools.deprecation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses japicmp's {@code target/japicmp/japicmp.xml} report into the same {@link RegistryKey}
 * identifier space the registry and the pom {@code <exclude>} entries use (D-22), carrying every
 * class/method/constructor/field element's {@code changeStatus}, old-side access modifier, and
 * binary-compatibility flag - the full-fidelity parse {@link RemovalConsistencyEvaluator} needs
 * for D-01's coverage/staleness checks and D-21's admissibility check.
 *
 * <p><strong>Structural limitation, load-bearing for {@link RemovalConsistencyEvaluator}:</strong>
 * once a symbol is named in the pom's {@code <excludes>} list, japicmp filters it out of the
 * comparison entirely, upstream of report generation (japicmp's own {@code Filters.includeClass}
 * / behavior-filter stage - see {@code 07-RESEARCH.md} "Priority 1"). A whole-class exclude makes
 * the entire {@code <class>} element vanish from this report; a member-level exclude makes only
 * that specific method/constructor/field vanish, while its enclosing class - if it still has other
 * members - remains visible. This report can therefore never re-confirm the exact
 * {@code changeStatus} or old-side modifier of an already-excluded symbol; it can only confirm
 * whether the symbol's enclosing class is still a real, comparison-visible class at all.
 */
public final class JapicmpReportReader {

    private static final Set<String> ACCESS_MODIFIER_VALUES = new HashSet<>(java.util.Arrays.asList(
            "PUBLIC", "PROTECTED", "PACKAGE_PROTECTED", "PRIVATE"));

    private JapicmpReportReader() {
    }

    /**
     * Reads and parses the japicmp report at {@code xmlFile}. Throws an {@link IOException}
     * naming the attempted path if the file does not exist or cannot be parsed - never returns a
     * silently-empty result for a missing or malformed input, which would make every downstream
     * consistency check pass vacuously.
     */
    public static Report read(Path xmlFile) throws IOException {
        if (!Files.exists(xmlFile)) {
            throw new IOException("japicmp report not found at " + xmlFile);
        }
        String xml = new String(Files.readAllBytes(xmlFile), StandardCharsets.UTF_8);
        return parse(xml, xmlFile.toString());
    }

    /**
     * Parses {@code xml} (already read into memory) into a {@link Report}. Package-visible so
     * tests can exercise it directly against checked-in fixtures without touching the filesystem
     * path-resolution behavior {@link #read(Path)} owns.
     */
    static Report parse(String xml, String sourceDescription) throws IOException {
        Document doc = parseXml(xml, sourceDescription);
        Element root = doc.getDocumentElement();
        String scope = root.getAttribute("accessModifier");

        Map<RegistryKey, Entry> entries = new LinkedHashMap<>();
        Element classesEl = firstChildElement(root, "classes");
        if (classesEl != null) {
            for (Element classElement : childElements(classesEl, "class")) {
                String className = classElement.getAttribute("fullyQualifiedName");
                entries.put(RegistryKey.forClass(className), entryFrom(classElement));

                collectMembers(classElement, "methods", "method", className, entries);
                collectMembers(classElement, "constructors", "constructor", className, entries);
                collectFields(classElement, className, entries);
            }
        }
        return new Report(scope, entries);
    }

    private static void collectMembers(Element classElement, String containerTag, String memberTag,
            String className, Map<RegistryKey, Entry> entries) {
        Element container = firstChildElement(classElement, containerTag);
        if (container == null) {
            return;
        }
        for (Element member : childElements(container, memberTag)) {
            String memberName = member.getAttribute("name");
            List<String> paramTypes = readParameterTypes(member);
            RegistryKey key = RegistryKey.forMember(className, memberName, paramTypes);
            entries.put(key, entryFrom(member));
        }
    }

    private static void collectFields(Element classElement, String className, Map<RegistryKey, Entry> entries) {
        Element container = firstChildElement(classElement, "fields");
        if (container == null) {
            return;
        }
        for (Element field : childElements(container, "field")) {
            RegistryKey key = RegistryKey.forField(className, field.getAttribute("name"));
            entries.put(key, entryFrom(field));
        }
    }

    private static Entry entryFrom(Element element) {
        String changeStatus = element.getAttribute("changeStatus");
        boolean binaryCompatible = "true".equals(element.getAttribute("binaryCompatible"));
        String oldModifier = readOldAccessModifier(element);
        return new Entry(changeStatus, oldModifier, binaryCompatible);
    }

    /**
     * Finds the one {@code <modifier>} entry (of the usual six: final, static, access, abstract,
     * bridge, synthetic) whose {@code oldValue} is a real {@link japicmp} access-level constant -
     * {@code PUBLIC}, {@code PROTECTED}, {@code PACKAGE_PROTECTED}, or {@code PRIVATE} (confirmed
     * via {@code javap -p} against {@code japicmp.model.AccessModifier} in japicmp-0.26.1.jar).
     * Returns {@code null} if the element carries no {@code <modifiers>} block at all (e.g. a
     * whole-class {@code REMOVED} entry's own superclass/interface sub-elements).
     */
    private static String readOldAccessModifier(Element element) {
        Element modifiersEl = firstChildElement(element, "modifiers");
        if (modifiersEl == null) {
            return null;
        }
        for (Element modifier : childElements(modifiersEl, "modifier")) {
            String oldValue = modifier.getAttribute("oldValue");
            if (ACCESS_MODIFIER_VALUES.contains(oldValue)) {
                return oldValue;
            }
        }
        return null;
    }

    private static List<String> readParameterTypes(Element member) {
        List<String> types = new ArrayList<>();
        Element parametersEl = firstChildElement(member, "parameters");
        if (parametersEl == null) {
            return types;
        }
        for (Element param : childElements(parametersEl, "parameter")) {
            types.add(param.getAttribute("type"));
        }
        return types;
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static Element firstChildElement(Element parent, String tagName) {
        List<Element> found = childElements(parent, tagName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static Document parseXml(String xml, String sourceDescription) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE hardening (T-07-03-04): the input is a locally-generated file today, but a
            // parser configured to fetch remote entities is a defect regardless of who currently
            // writes it.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse japicmp report at " + sourceDescription
                    + ": " + e.getMessage(), e);
        }
    }

    /** One parsed report element's comparison data. */
    public static final class Entry {
        private final String changeStatus;
        private final String oldAccessModifier;
        private final boolean binaryCompatible;

        Entry(String changeStatus, String oldAccessModifier, boolean binaryCompatible) {
            this.changeStatus = changeStatus;
            this.oldAccessModifier = oldAccessModifier;
            this.binaryCompatible = binaryCompatible;
        }

        public String getChangeStatus() {
            return changeStatus;
        }

        /** The bytecode-derived old-side access modifier, or {@code null} if this element carries no modifiers block. */
        public String getOldAccessModifier() {
            return oldAccessModifier;
        }

        public boolean isBinaryCompatible() {
            return binaryCompatible;
        }
    }

    /** The full parsed report: every class/method/constructor/field element, plus the root scope. */
    public static final class Report {
        private final String accessModifier;
        private final Map<RegistryKey, Entry> entries;

        Report(String accessModifier, Map<RegistryKey, Entry> entries) {
            this.accessModifier = accessModifier;
            this.entries = entries;
        }

        public static Report empty() {
            return new Report(null, Collections.emptyMap());
        }

        /** The report's root {@code accessModifier} attribute - the scope this comparison covers. */
        public String accessModifier() {
            return accessModifier;
        }

        public Map<RegistryKey, Entry> entries() {
            return entries;
        }

        public Optional<Entry> find(RegistryKey key) {
            return Optional.ofNullable(entries.get(key));
        }
    }
}
