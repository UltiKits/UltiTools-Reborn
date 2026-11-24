"""The framework release is a Paper plugin; module plugin.yml files are unrelated."""

import importlib.util
import io
import unittest
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "release_artifacts", Path(__file__).resolve().parent.parent / "release_artifacts.py")
assert SPEC is not None and SPEC.loader is not None
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)
DESCRIPTOR = b"name: UltiTools\nversion: '7.0.0'\nmain: fixture.Main\napi-version: '26.3'\n"


class ReleaseDescriptorTests(unittest.TestCase):
    def inspect(self, descriptors):
        content = io.BytesIO()
        with zipfile.ZipFile(content, "w") as archive:
            archive.writestr("fixture/Main.class", b"class fixture")
            for name in descriptors:
                archive.writestr(name, DESCRIPTOR)
        problems = HELPER.Problems()
        entry, values = HELPER.inspect_primary(content.getvalue(), "7.0.0", problems)
        return problems.items, entry, values

    def test_accepts_only_the_paper_descriptor_at_the_root(self):
        problems, entry, values = self.inspect(["paper-plugin.yml"])
        self.assertEqual(problems, [])
        self.assertEqual(entry, "fixture/Main.class")
        self.assertEqual(values["api-version"], "26.3")

    def test_refuses_a_root_legacy_descriptor_even_with_a_paper_descriptor(self):
        for descriptors in (["plugin.yml"], ["paper-plugin.yml", "plugin.yml"]):
            with self.subTest(descriptors=descriptors):
                problems, _, _ = self.inspect(descriptors)
                self.assertIn("legacy_plugin_yml_present", [code for code, _ in problems])

    def test_nested_descriptor_does_not_satisfy_the_root_requirement(self):
        problems, _, _ = self.inspect(["nested/paper-plugin.yml"])
        self.assertIn("paper_plugin_yml_missing", [code for code, _ in problems])

    def test_module_descriptor_nested_in_the_jar_is_not_a_root_legacy_descriptor(self):
        problems, _, _ = self.inspect(["paper-plugin.yml", "fixture/plugin.yml"])
        self.assertEqual(problems, [])


if __name__ == "__main__":
    unittest.main()
