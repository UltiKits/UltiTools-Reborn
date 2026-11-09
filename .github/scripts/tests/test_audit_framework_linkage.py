"""Mechanical isolation contracts for the offline framework CI audit helper."""

import contextlib
import dataclasses
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as element_tree
from defusedxml.ElementTree import fromstring as parse_xml_string, parse as parse_xml
from defusedxml.common import EntitiesForbidden
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "audit_framework_linkage.py"
SPEC = importlib.util.spec_from_file_location("audit_framework_linkage", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
AUDIT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = AUDIT
SPEC.loader.exec_module(AUDIT)


class FrameworkLinkageClasspathTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def jar(self, name, entry="safe/Library.class", group="fixture"):
        path = self.root / (name + ".jar")
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr(entry, b"fixture")
        return AUDIT.Dependency(group, name, path)

    def test_rejects_every_server_prefix_and_multi_release_entry(self):
        safe = self.jar("safe")
        forbidden = [self.jar("bad" + str(index), prefix + "Api.class")
                     for index, prefix in enumerate(AUDIT.SERVER_PREFIXES)]
        forbidden.append(self.jar("multi", "META-INF/versions/21/org/bukkit/Api.class"))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            added = AUDIT.framework_resolution([safe] + forbidden + [safe], [])
        self.assertEqual([safe.path], added)
        self.assertEqual(len(AUDIT.SERVER_PREFIXES) + 1, output.getvalue().count("forbidden server entry"))
        self.assertIn("Framework resolution-only JARs (1):", output.getvalue())

    def test_guard_covers_every_namespace_the_java_audit_treats_as_api(self):
        for prefix in ("org/bukkit/", "io/papermc/", "com/destroystokyo/", "net/kyori/",
                       "co/aikar/", "org/spigotmc/", "com/ultikits/ultitools/"):
            self.assertIn(prefix, AUDIT.SERVER_PREFIXES)

    def test_excludes_whole_modern_graph_even_for_other_versions(self):
        paper = self.jar("paper-api", group="io.papermc.paper")
        modern_child = self.jar("adventure-api", group="net.kyori")
        framework_child = AUDIT.Dependency("net.kyori", "adventure-api", self.jar("older-adventure").path)
        safe = self.jar("sqlite-jdbc")
        with contextlib.redirect_stdout(io.StringIO()):
            added = AUDIT.framework_resolution([paper, framework_child, safe], [paper, modern_child])
        self.assertEqual([safe.path], added)

    def test_dependency_list_parses_classifiers_and_filters_test_system_nonjar(self):
        jar = self.jar("dependency")

        def resolve(command, **kwargs):
            output = Path(next(argument.split("=", 1)[1] for argument in command
                               if argument.startswith("-DoutputFile=")))
            output.write_text("\n".join([
                "The following files have been resolved:",
                "   fixture:dep:jar:1:provided:" + str(jar.path) + " -- module dep (auto)",
                "   fixture:classified:jar:all:1:runtime:" + str(jar.path),
                "   fixture:test:jar:1:test:" + str(jar.path),
                "   fixture:system:jar:1:system:" + str(jar.path),
                "   fixture:bom:pom:1:compile:" + str(jar.path),
                "   fixture:optional:jar:2:compile:" + str(jar.path) + " (optional) -- module optional",
                "   fixture:optional-plain:jar:3:runtime:" + str(jar.path) + " (optional)",
            ]), encoding="utf-8")

        with patch.object(AUDIT.subprocess, "run", side_effect=resolve):
            found = AUDIT.resolve_dependencies(self.root / "pom.xml", self.root, "compile")
        self.assertEqual([("fixture", "dep"), ("fixture", "classified"), ("fixture", "optional"),
                          ("fixture", "optional-plain")], [item.key for item in found])
        self.assertEqual(["1", "1", "2", "3"], [item.version for item in found])

    def test_old_graph_must_resolve_the_pinned_paper_line(self):
        pinned = "1.19.2-R0.1-20221206.184705-189"
        old = AUDIT.Dependency("io.papermc.paper", "paper-api", self.root / "p.jar", "1.19.2-R0.1-SNAPSHOT")
        AUDIT.require_pinned_paper([old], pinned)
        modern = AUDIT.Dependency("io.papermc.paper", "paper-api", self.root / "p.jar", "1.21.11-R0.1-SNAPSHOT")
        for graph in ([modern], [], [old, modern]):
            with self.assertRaises(ValueError):
                AUDIT.require_pinned_paper(graph, pinned)

    def test_resolution_failure_cannot_reuse_stale_output(self):
        output = self.root / "dependencies-runtime.txt"
        output.write_text("stale", encoding="utf-8")
        with patch.object(AUDIT.subprocess, "run", side_effect=subprocess.CalledProcessError(7, "mvn")):
            with self.assertRaises(subprocess.CalledProcessError):
                AUDIT.resolve_dependencies(self.root / "pom.xml", self.root, "runtime")
        self.assertFalse(output.exists())

    def test_modern_paper_graph_is_resolved_as_independent_root(self):
        project = parse_xml_string('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <properties><paper.version>1.21.11-R0.1-SNAPSHOT</paper.version></properties>
          <repositories><repository><id>paper</id><url>https://example.invalid/</url></repository></repositories>
          <dependencies><dependency><groupId>unrelated</groupId><artifactId>not-paper</artifactId>
          <version>1</version></dependency></dependencies></project>''')
        path = AUDIT.paper_pom(project, self.root, "${paper.version}")
        self.assertNotIn("ns0:", path.read_text(encoding="utf-8"))
        generated = parse_xml(path).getroot()
        dependencies = generated.findall("m:dependencies/m:dependency", AUDIT.NAMESPACE)
        self.assertEqual(1, len(dependencies))
        self.assertEqual("paper-api", AUDIT.required_text(dependencies[0], "m:artifactId"))
        self.assertEqual("${paper.version}", AUDIT.required_text(dependencies[0], "m:version"))
        self.assertEqual("1.21.11-R0.1-SNAPSHOT", AUDIT.required_text(generated, "m:properties/m:paper.version"))
        hostile = self.root / "entity.xml"
        hostile.write_text('<!DOCTYPE project [<!ENTITY value "expanded">]><project>&value;</project>',
                           encoding="utf-8")
        with self.assertRaises(EntitiesForbidden):
            AUDIT.parse_xml(hostile)

    def test_old_paper_pom_never_copies_dependency_management(self):
        project = parse_xml_string('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <properties><paper.version>1.21.11-R0.1-SNAPSHOT</paper.version></properties>
          <repositories><repository><id>paper</id><url>https://example.invalid/</url></repository></repositories>
          <dependencyManagement><dependencies><dependency><groupId>net.kyori</groupId>
          <artifactId>adventure-api</artifactId><version>4.26.1</version></dependency></dependencies>
          </dependencyManagement></project>''')
        old = parse_xml(AUDIT.paper_pom(project, self.root / "old", "1.19.2-R0.1-SNAPSHOT")).getroot()
        self.assertIsNone(old.find("m:dependencyManagement", AUDIT.NAMESPACE))
        self.assertIsNotNone(old.find("m:properties", AUDIT.NAMESPACE))
        self.assertIsNotNone(old.find("m:repositories", AUDIT.NAMESPACE))
        modern = parse_xml(AUDIT.paper_pom(project, self.root / "modern", "${paper.version}",
                                           managed=True)).getroot()
        self.assertIsNotNone(modern.find("m:dependencyManagement", AUDIT.NAMESPACE))

    def main_fixture(self):
        framework = self.jar("framework")
        supplemental = self.jar("sqlite-jdbc")
        old = self.jar("paper-api", group="io.papermc.paper")
        old_child = self.jar("old-child")
        modern = self.jar("paper-api-modern", group="io.papermc.paper")
        project = parse_xml_string('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <version>1</version><dependencies><dependency><groupId>io.papermc.paper</groupId>
          <artifactId>paper-api</artifactId><version>modern</version></dependency></dependencies></project>''')
        original_cwd = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, original_cwd)
        (self.root / "pom.xml").write_text("fixture", encoding="utf-8")

        def resolve(pom, directory, scope):
            if directory.name == "modern-paper":
                return [modern]
            if directory.name == "framework":
                return [supplemental]
            pinned_base = directory.name.rsplit("-", 2)[0] + "-SNAPSHOT"
            return [old_child, dataclasses.replace(old, version=pinned_base)]

        return framework, supplemental, old, old_child, project, resolve

    def test_nonzero_java_exit_propagates_through_entry(self):
        framework, _, _, _, project, resolve = self.main_fixture()
        for status in (1, 3):
            with patch.object(AUDIT, "parse_xml", return_value=element_tree.ElementTree(project)), \
                    patch.object(AUDIT, "resolve_dependencies", side_effect=resolve), \
                    patch.object(AUDIT.subprocess, "run",
                                 side_effect=subprocess.CalledProcessError(status, "java")), \
                    patch.object(sys, "argv", [str(SCRIPT), str(framework.path)]), \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(status, AUDIT.entry())
            self.assertNotIn("0 unresolved references", output.getvalue())

    def test_main_uses_old_graph_first_and_only_framework_as_subject(self):
        framework, supplemental, old, old_child, project, resolve = self.main_fixture()
        invocations = []
        with patch.object(AUDIT, "parse_xml", return_value=element_tree.ElementTree(project)), \
                patch.object(AUDIT, "resolve_dependencies", side_effect=resolve), \
                patch.object(AUDIT.subprocess, "run", side_effect=lambda args, **kw: invocations.append(args)), \
                patch.object(sys, "argv", [str(SCRIPT), str(framework.path)]), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, AUDIT.main())
        self.assertEqual(2, len(invocations))
        for command in invocations:
            self.assertEqual([str(old.path), str(old_child.path), str(supplemental.path)],
                             command[command.index("--api") + 1].split(os.pathsep))
            self.assertEqual(["--own", "com/ultikits/ultitools/", str(framework.path)], command[-3:])
            self.assertEqual(1, command.count(str(framework.path)))


if __name__ == "__main__":
    unittest.main()
