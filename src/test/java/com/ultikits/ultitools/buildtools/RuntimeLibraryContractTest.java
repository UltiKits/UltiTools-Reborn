package com.ultikits.ultitools.buildtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.ultikits.ultitools.manager.DependenceManagers;

/**
 * Guards the framework's runtime-library contract at the source and compiled-output boundaries.
 * Paper supplies the native Adventure audience; the retired platform provider must not return.
 */
@DisplayName("Framework runtime-library contract")
class RuntimeLibraryContractTest {

    private static final String AUDIENCE_PROVIDER = "net.kyori:adventure-platform-bukkit";
    private static final String PLATFORM_PACKAGE = "net/kyori/adventure/platform";

    @Test
    void pluginYmlDeclaresNoAudienceProviderLibrary() throws Exception {
        Path pluginYml = projectRoot().resolve("target/classes/plugin.yml");
        assertThat(pluginYml).as("the filtered plugin descriptor must exist").isRegularFile();

        Object descriptor;
        try (InputStream input = Files.newInputStream(pluginYml)) {
            descriptor = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
        assertThat(descriptor).isInstanceOf(Map.class);
        Object libraries = ((Map<?, ?>) descriptor).get("libraries");
        assertThat(libraries).as("the filtered descriptor's libraries list").isInstanceOf(List.class);

        List<String> providerLibraries = new ArrayList<>();
        for (Object library : (List<?>) libraries) {
            assertThat(library).as("a Paper library coordinate").isInstanceOf(String.class);
            String coordinate = (String) library;
            String[] parts = coordinate.split(":");
            if (parts.length >= 2 && AUDIENCE_PROVIDER.equals(parts[0] + ":" + parts[1])) {
                providerLibraries.add(coordinate);
            }
        }
        assertThat(providerLibraries)
                .as("filtered plugin.yml must not load the adventure-platform audience provider")
                .isEmpty();
    }

    @Test
    void compiledFrameworkClassesDoNotReferenceThePlatformPackage() throws Exception {
        Path classRoot = projectRoot().resolve("target/classes/com/ultikits");
        assertThat(classRoot).as("compiled framework classes must exist").isDirectory();
        List<Path> classFiles;
        try (Stream<Path> paths = Files.walk(classRoot)) {
            classFiles = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted().collect(Collectors.toList());
        }
        assertThat(classFiles).as("the scan must cover real compiled classes").isNotEmpty();

        List<String> bukkitReferences = classesReferencing(classFiles, classRoot, "org/bukkit");
        assertThat(bukkitReferences)
                .as("the same binary scanner must find the known org/bukkit positive control")
                .isNotEmpty();
        System.out.println("Runtime library binary scan: classes=" + classFiles.size()
                + ", org/bukkit positive-control classes=" + bukkitReferences.size());

        List<String> platformReferences = classesReferencing(classFiles, classRoot, PLATFORM_PACKAGE);
        assertThat(platformReferences)
                .as("compiled framework classes must not reference the adventure-platform package")
                .isEmpty();
    }

    @Test
    void dependenceManagersExposesNoAudienceProvider() {
        List<String> providerMembers = new ArrayList<>();
        for (Method method : DependenceManagers.class.getDeclaredMethods()) {
            if (isAudienceProviderMember(method.getName())) {
                providerMembers.add("method " + method.getName());
            }
        }
        for (Field field : DependenceManagers.class.getDeclaredFields()) {
            if (isAudienceProviderMember(field.getName())) {
                providerMembers.add("field " + field.getName());
            }
        }
        Collections.sort(providerMembers);
        assertThat(providerMembers)
                .as("DependenceManagers must declare no audience-provider methods or fields")
                .isEmpty();
    }

    @Test
    void pomDeclaresNoAudienceProviderDependency() throws Exception {
        Path pom = projectRoot().resolve("pom.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document;
        try (InputStream input = Files.newInputStream(pom)) {
            document = factory.newDocumentBuilder().parse(input);
        }

        List<String> providerDependencies = new ArrayList<>();
        NodeList dependencies = document.getElementsByTagNameNS("*", "dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String coordinate = childText(dependency, "groupId") + ":"
                    + childText(dependency, "artifactId");
            if (AUDIENCE_PROVIDER.equals(coordinate)) {
                providerDependencies.add(coordinate);
            }
        }
        assertThat(providerDependencies)
                .as("pom.xml must not depend on the adventure-platform audience provider")
                .isEmpty();
    }

    @Test
    void pluginYmlLibrariesAreExactlyTheDecidedSet() throws Exception {
        // Paper 1.19.2 builds 163 and 307 supply Gson, MySQL, protobuf and slf4j.
        // Decision: 18-32-paper-1.19.2-libraries measurement, 2026-10-10.
        Set<String> expected = new HashSet<>(Arrays.asList(
                "commons-dbutils:commons-dbutils", "org.java-websocket:Java-WebSocket",
                "net.bytebuddy:byte-buddy", "com.sun.mail:javax.mail",
                "com.zaxxer:HikariCP", "com.github.cryptomorin:XSeries"));
        List<String> coordinates = libraryCoordinates();
        List<String> actual = coordinates.stream()
                .map(coordinate -> coordinate.substring(0, coordinate.lastIndexOf(':')))
                .collect(Collectors.toList());
        assertThat(actual).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void everyLibraryVersionEqualsThePomVersion() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document pom;
        try (InputStream input = Files.newInputStream(projectRoot().resolve("pom.xml"))) {
            pom = factory.newDocumentBuilder().parse(input);
        }
        Map<String, String> versions = new LinkedHashMap<>();
        NodeList dependencies = pom.getElementsByTagNameNS("*", "dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            versions.put(childText(dependency, "groupId") + ":" + childText(dependency, "artifactId"),
                    childText(dependency, "version"));
        }
        for (String coordinate : libraryCoordinates()) {
            String[] parts = coordinate.split(":");
            assertThat(versions).as("POM declares " + coordinate).containsKey(parts[0] + ":" + parts[1]);
            assertThat(parts[2]).as(coordinate + " matches its compile dependency")
                    .isEqualTo(versions.get(parts[0] + ":" + parts[1]));
        }
    }

    private static List<String> libraryCoordinates() throws Exception {
        Object descriptor;
        try (InputStream input = Files.newInputStream(projectRoot().resolve("target/classes/plugin.yml"))) {
            descriptor = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
        assertThat(descriptor).isInstanceOf(Map.class);
        Object libraries = ((Map<?, ?>) descriptor).get("libraries");
        assertThat(libraries).isInstanceOf(List.class);
        List<String> result = new ArrayList<>();
        for (Object library : (List<?>) libraries) {
            assertThat(library).isInstanceOf(String.class);
            String coordinate = (String) library;
            assertThat(coordinate.split(":")).as("Maven library coordinate").hasSize(3);
            result.add(coordinate);
        }
        return result;
    }

    /**
     * Prefer Maven's project identity; only fall back to walking up from the working directory.
     * A Maven invocation using -f must not accidentally read another checkout (#461).
     */
    private static Path projectRoot() {
        String basedir = System.getProperty("basedir");
        Path candidate = basedir == null || basedir.trim().isEmpty()
                ? Paths.get("").toAbsolutePath().normalize()
                : Paths.get(basedir).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate the Maven project root containing pom.xml");
    }

    private static boolean isAudienceProviderMember(String name) {
        return name.contains("Adventure") || "adventure".equals(name);
    }

    private static String childText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getLocalName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static List<String> classesReferencing(List<Path> classFiles, Path classRoot,
            String internalName) throws Exception {
        byte[] needle = internalName.getBytes(StandardCharsets.US_ASCII);
        List<String> references = new ArrayList<>();
        for (Path classFile : classFiles) {
            if (containsBytes(Files.readAllBytes(classFile), needle)) {
                references.add(classRoot.relativize(classFile).toString());
            }
        }
        return references;
    }

    /** Scans bytes directly, including constant-pool strings, without a text decoder or grep. */
    private static boolean containsBytes(byte[] bytes, byte[] needle) {
        for (int start = 0; start <= bytes.length - needle.length; start++) {
            int offset = 0;
            while (offset < needle.length && bytes[start + offset] == needle[offset]) {
                offset++;
            }
            if (offset == needle.length) {
                return true;
            }
        }
        return false;
    }
}
