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
        # Skipping the fixture here preserves missing/changed files for the offline assertion.
        warm_args = ["-Druntime.api.floor.skip=true"] if artifact == "maven-antrun-plugin" else []
        warm = subprocess.run(["mvn", "-B", "-ntp", *goals, *args, *warm_args], cwd=self.root, env=env,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=660)
        warm_output = warm.stdout.decode("utf-8", "replace")
        self.assertEqual(warm.returncode, 0, "Probe dependency warm-up failed:\n" + warm_output)
        result = subprocess.run(["mvn", "-B", "-o", "-ntp", *goals, *args], cwd=self.root, env=env,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=660)
        return result, warm_output + result.stdout.decode("utf-8", "replace")

    def floor(self, *args):
        return self.run_mojo("maven-antrun-plugin", "runtime-api-floor-paper-fixture",
                             "-Dfloor.local=" + str(self.root / "missing.jar"),
                             "-Dfloor.url=" + (self.root / "unavailable.jar").as_uri(), *args)

    def test_missing_fixture_explains_failure_and_opt_out(self):
        result, output = self.floor()
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn("Runtime API floor fixture missing", output)
        self.assertIn("-Druntime.api.floor.skip=true", output)

    def test_changed_fixture_explains_failure_and_opt_out(self):
        fixture = self.root / "target/runtime-api-floor/paper-api-1.19.2.jar"
        fixture.parent.mkdir(parents=True)
        fixture.write_bytes(b"changed")
        result, output = self.floor()
        self.assertNotEqual(result.returncode, 0, output)
        self.assertIn("Runtime API floor fixture changed", output)
        self.assertIn("-Druntime.api.floor.skip=true", output)

    def test_offline_opt_out_does_not_resolve_or_create_the_paper_fixture(self):
        result, output = self.floor("-Druntime.api.floor.skip=true")
        self.assertEqual(result.returncode, 0, output)
        self.assertFalse((self.root / "target/runtime-api-floor/paper-api-1.19.2.jar").exists())

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
