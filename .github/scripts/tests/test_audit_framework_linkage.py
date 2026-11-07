"""Mechanical isolation contracts for the offline framework CI audit helper."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
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
        self.assertEqual(5, output.getvalue().count("forbidden server entry"))
        self.assertIn("Framework resolution-only JARs (1):", output.getvalue())

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
            ]), encoding="utf-8")

        with patch.object(AUDIT.subprocess, "run", side_effect=resolve):
            found = AUDIT.resolve_dependencies(self.root / "pom.xml", self.root, "compile")
        self.assertEqual([("fixture", "dep"), ("fixture", "classified")], [item.key for item in found])

    def test_resolution_failure_cannot_reuse_stale_output(self):
        output = self.root / "dependencies-runtime.txt"
        output.write_text("stale", encoding="utf-8")
        with patch.object(AUDIT.subprocess, "run", side_effect=subprocess.CalledProcessError(7, "mvn")):
            with self.assertRaises(subprocess.CalledProcessError):
                AUDIT.resolve_dependencies(self.root / "pom.xml", self.root, "runtime")
        self.assertFalse(output.exists())

    def test_modern_paper_graph_is_resolved_as_independent_root(self):
        project = ET.fromstring('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <properties><paper.version>1.21.11-R0.1-SNAPSHOT</paper.version></properties>
          <repositories><repository><id>paper</id><url>https://example.invalid/</url></repository></repositories>
          <dependencies><dependency><groupId>unrelated</groupId><artifactId>not-paper</artifactId>
          <version>1</version></dependency></dependencies></project>''')
        path = AUDIT.paper_pom(project, self.root, "${paper.version}")
        self.assertNotIn("ns0:", path.read_text(encoding="utf-8"))
        generated = ET.parse(path).getroot()
        dependencies = generated.findall("m:dependencies/m:dependency", AUDIT.NAMESPACE)
        self.assertEqual(1, len(dependencies))
        self.assertEqual("paper-api", AUDIT.required_text(dependencies[0], "m:artifactId"))
        self.assertEqual("${paper.version}", AUDIT.required_text(dependencies[0], "m:version"))
        self.assertEqual("1.21.11-R0.1-SNAPSHOT", AUDIT.required_text(generated, "m:properties/m:paper.version"))

    def test_main_uses_old_graph_first_and_only_framework_as_subject(self):
        framework = self.jar("framework")
        supplemental = self.jar("sqlite-jdbc")
        old = self.jar("paper-api", group="io.papermc.paper")
        old_child = self.jar("old-child")
        modern = self.jar("paper-api-modern", group="io.papermc.paper")
        project = ET.fromstring('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <version>1</version><dependencies><dependency><groupId>io.papermc.paper</groupId>
          <artifactId>paper-api</artifactId><version>modern</version></dependency></dependencies></project>''')
        invocations = []
        original_cwd = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, original_cwd)
        (self.root / "pom.xml").write_text("fixture", encoding="utf-8")

        def resolve(pom, directory, scope):
            if directory.name == "modern-paper":
                return [modern]
            if directory.name == "framework":
                return [supplemental]
            return [old_child, old]

        with patch.object(AUDIT.ET, "parse", return_value=ET.ElementTree(project)), \
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
