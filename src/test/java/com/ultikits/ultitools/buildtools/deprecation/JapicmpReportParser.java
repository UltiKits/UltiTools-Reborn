package com.ultikits.ultitools.buildtools.deprecation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.xml.sax.SAXException;

/**
 * Reads japicmp's {@code target/japicmp/japicmp.xml} report and finds every symbol reported
 * {@code changeStatus="REMOVED"} - the japicmp-side half of D-22's dual-source REMOVED check.
 *
 * <p>Reconstructs {@link RegistryKey}s from the report's own {@code fullyQualifiedName} /
 * {@code name} / {@code <parameter type=...>} attributes, so the japicmp side and the source-scan
 * side share the exact same string form (D-01).
 */
final class JapicmpReportParser {

    private JapicmpReportParser() {
    }

    static Set<RegistryKey> findRemovedKeys(String xml) throws IOException {
        Set<RegistryKey> keys = new LinkedHashSet<>();
        Document doc = parse(xml);
        NodeList classes = doc.getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            Element classElement = (Element) classes.item(i);
            String className = classElement.getAttribute("fullyQualifiedName");

            if ("REMOVED".equals(classElement.getAttribute("changeStatus"))) {
                keys.add(RegistryKey.forClass(className));
            }

            collectMembers(classElement, "methods", "method", className, false, keys);
            collectMembers(classElement, "constructors", "constructor", className, true, keys);
            collectFields(classElement, className, keys);
        }
        return keys;
    }

    private static void collectMembers(Element classElement, String containerTag, String memberTag,
            String className, boolean isConstructor, Set<RegistryKey> keys) {
        Element container = firstChildElement(classElement, containerTag);
        if (container == null) {
            return;
        }
        NodeList members = container.getElementsByTagName(memberTag);
        for (int i = 0; i < members.getLength(); i++) {
            Element member = (Element) members.item(i);
            if (!"REMOVED".equals(member.getAttribute("changeStatus"))) {
                continue;
            }
            String memberName = member.getAttribute("name");
            List<String> paramTypes = readParameterTypes(member);
            keys.add(RegistryKey.forMember(className, memberName, paramTypes));
        }
    }

    private static void collectFields(Element classElement, String className, Set<RegistryKey> keys) {
        Element container = firstChildElement(classElement, "fields");
        if (container == null) {
            return;
        }
        NodeList fields = container.getElementsByTagName("field");
        for (int i = 0; i < fields.getLength(); i++) {
            Element field = (Element) fields.item(i);
            if (!"REMOVED".equals(field.getAttribute("changeStatus"))) {
                continue;
            }
            keys.add(RegistryKey.forField(className, field.getAttribute("name")));
        }
    }

    private static List<String> readParameterTypes(Element member) {
        List<String> types = new ArrayList<>();
        Element parametersEl = firstChildElement(member, "parameters");
        if (parametersEl == null) {
            return types;
        }
        NodeList params = parametersEl.getElementsByTagName("parameter");
        for (int i = 0; i < params.getLength(); i++) {
            Element param = (Element) params.item(i);
            types.add(param.getAttribute("type"));
        }
        return types;
    }

    private static Element firstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static Document parse(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse japicmp.xml", e);
        }
    }
}
