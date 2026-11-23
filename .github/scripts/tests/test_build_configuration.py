"""Exercise the real build mojos in tiny offline projects, without a server or credentials."""
import copy
import os
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as element_tree
from pathlib import Path

from defusedxml.ElementTree import parse as parse_xml

ROOT = Path(__file__).resolve().parents[3]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
POM = parse_xml(ROOT / "pom.xml")


def required(parent, path, namespaces=None):
    element = parent.find(path, namespaces)
    if element is None:
        raise AssertionError("Missing build configuration: " + path)
    return element


def local_tag(element):
    element.tag = element.tag.split("}")[-1]
    for child in element:
        local_tag(child)
    return element


class BuildConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="build-configuration-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_mojo(self, artifact, execution, *args):
        original = required(POM, "m:build/m:plugins/m:plugin[m:artifactId='%s']" % artifact, NS)
        plugin = local_tag(copy.deepcopy(original))
        executions = required(plugin, "executions")
        for item in list(executions):
            if item.findtext("id") != execution:
                executions.remove(item)
        # Match the real execution while keeping unrelated framework dependencies out of this probe.
        configuration = plugin.find("configuration")
        if configuration is not None:
            plugin.remove(configuration)
        project = element_tree.Element("project")
        for name, value in (("modelVersion", "4.0.0"), ("groupId", "probe"),
                            ("artifactId", "build-config"), ("version", "1")):
            element_tree.SubElement(project, name).text = value
        properties = local_tag(copy.deepcopy(required(POM, "m:properties", NS)))
        project.append(properties)
        # Offline resolution must recognise the repository IDs used by CI's online warm-up.
        for section in ("repositories", "pluginRepositories"):
            repositories = POM.find("m:" + section, NS)
            if repositories is not None:
                project.append(local_tag(copy.deepcopy(repositories)))
        plugins = element_tree.SubElement(element_tree.SubElement(project, "build"), "plugins")
        plugins.append(plugin)
        (self.root / "pom.xml").write_bytes(element_tree.tostring(project))
        env = dict(os.environ)
        env.pop("MAVEN_ARGS", None)
        selected = required(plugin, "executions/execution/goals/goal").text
        assert selected is not None
        prefix = "org.apache.maven.plugins:" + artifact + ":" + plugin.findtext("version") + ":"
        goals = [prefix + "compile"] if artifact == "maven-compiler-plugin" else []
        goals.append(prefix + selected + "@" + execution)
        # Resolve this probe's actual plugin realm online, not just the framework's realm.
        warm = subprocess.run(["mvn", "-B", "-ntp", *goals, *args], cwd=self.root, env=env,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=660)
        warm_output = warm.stdout.decode("utf-8", "replace")
        self.assertEqual(warm.returncode, 0, "Probe dependency warm-up failed:\n" + warm_output)
        result = subprocess.run(["mvn", "-B", "-o", "-ntp", *goals, *args], cwd=self.root, env=env,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=660)
        return result, warm_output + result.stdout.decode("utf-8", "replace")

    def test_paper_baseline_is_pinned_and_jsr305_is_explicit(self):
        dependencies = required(POM, "m:dependencies", NS)
        paper = required(dependencies, "m:dependency[m:artifactId='paper-api']", NS)
        self.assertEqual(paper.findtext("m:version", namespaces=NS), "26.3.build.258-stable")
        self.assertEqual(paper.findtext("m:scope", namespaces=NS), "provided")
        annotations = required(dependencies, "m:dependency[m:artifactId='jsr305']", NS)
        self.assertEqual(annotations.findtext("m:version", namespaces=NS), "3.0.2")
        self.assertEqual(annotations.findtext("m:scope", namespaces=NS), "provided")
        ignored = POM.findall(".//m:ignoredUsedUndeclaredDependency", NS)
        self.assertNotIn("com.google.code.findbugs:jsr305", [item.text for item in ignored])

    def test_java25_release_replaces_the_java8_signature_guard(self):
        self.assertEqual(required(POM, "m:properties/m:java.version", NS).text, "25")
        plugins = required(POM, "m:build/m:plugins", NS)
        compiler = required(plugins, "m:plugin[m:artifactId='maven-compiler-plugin']/m:configuration", NS)
        self.assertEqual(compiler.findtext("m:release", namespaces=NS), "${java.version}")
        self.assertIsNone(compiler.find("m:source", NS))
        self.assertIsNone(compiler.find("m:target", NS))
        self.assertIsNone(plugins.find("m:plugin[m:artifactId='animal-sniffer-maven-plugin']", NS))

    def test_old_paper_fixtures_are_retired_but_packaged_runtime_guard_remains(self):
        properties = required(POM, "m:properties", NS)
        self.assertFalse(any(item.tag.split("}")[-1].startswith("runtime.api.floor.")
                             for item in properties))
        executions = POM.findall("m:build/m:plugins/m:plugin/m:executions/m:execution/m:id", NS)
        self.assertFalse(any((item.text or "").startswith("runtime-api-floor") for item in executions))
        failsafe = required(POM, "m:build/m:plugins/m:plugin[m:artifactId='maven-failsafe-plugin']", NS)
        goals = [item.text for item in failsafe.findall("m:executions/m:execution/m:goals/m:goal", NS)]
        self.assertIn("integration-test", goals)
        self.assertIn("verify", goals)
        self.assertEqual(failsafe.findtext("m:configuration/m:includes/m:include", namespaces=NS),
                         "**/PackagedRuntimeLibraryIT.java")
        self.assertEqual(failsafe.findtext(
            "m:configuration/m:systemPropertyVariables/m:runtimeGuardArtifact", namespaces=NS),
            "${project.build.directory}/${project.build.finalName}.jar")
        self.assertNotIn("audit_framework_linkage.py",
                         (ROOT / ".github/workflows/maven-ci.yml").read_text())

    def test_helper_compile_does_not_replace_the_product_artifact_even_with_tests_skipped(self):
        source = self.root / "src/test/java/com/ultikits/ultitools/buildtools/deprecation/Helper.java"
        source.parent.mkdir(parents=True)
        source.write_text("package com.ultikits.ultitools.buildtools.deprecation; public class Helper {}")
        main = self.root / "src/main/java/Product.java"
        main.parent.mkdir(parents=True)
        main.write_text("public class Product {}")
        # Run the normal compile first in the same session so it assigns the product artifact.
        result, output = self.run_mojo("maven-compiler-plugin", "compile-deprecation-buildtools",
                                      "-Dmaven.test.skip=true")
        self.assertEqual(result.returncode, 0, output)
        self.assertNotIn("Overwriting artifact's file", output)
        self.assertTrue((self.root / "target/classes/Product.class").is_file())
        self.assertTrue((self.root / "target/buildtools-classes/com/ultikits/ultitools/buildtools/deprecation/Helper.class").is_file())
        self.assertFalse((self.root / "target/classes/com/ultikits/ultitools/buildtools/deprecation").exists())


if __name__ == "__main__":
    unittest.main()
