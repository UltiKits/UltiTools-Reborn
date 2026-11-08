#!/usr/bin/env python3
"""Audit the shaded framework against isolated, timestamp-pinned old Paper APIs."""

import copy
import os
import subprocess
import sys
import xml.etree.ElementTree as element_tree
from defusedxml.ElementTree import parse as parse_xml
import zipfile
from dataclasses import dataclass
from pathlib import Path


PAPER_VERSIONS = (
    "1.19.2-R0.1-20221206.184705-189",
    "1.20.6-R0.1-20241030.191541-127",
)
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
DEPENDENCY_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:"
SERVER_PREFIXES = ("org/bukkit/", "io/papermc/", "net/minecraft/", "org/spigotmc/")
OWN_PREFIX = "com/ultikits/ultitools/"
PAPER = ("io.papermc.paper", "paper-api")


@dataclass(frozen=True)
class Dependency:
    group: str
    artifact: str
    path: Path

    @property
    def key(self) -> tuple[str, str]:
        # Exclude every version/classifier of a dependency in modern Paper's graph.
        return self.group, self.artifact


def required_text(element: element_tree.Element, path: str) -> str:
    value = element.findtext(path, namespaces=NAMESPACE)
    if value is None or not value.strip():
        raise ValueError("Missing POM value: " + path)
    return value.strip()


def resolve_dependencies(pom: Path, directory: Path, scope: str) -> list[Dependency]:
    """Let Maven resolve versions, scopes, exclusions and transitive dependencies."""
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / ("dependencies-" + scope + ".txt")
    output.unlink(missing_ok=True)
    subprocess.run(["mvn", "-B", "-ntp", "-f", str(pom), DEPENDENCY_PLUGIN + "list",
                    "-DincludeScope=" + scope, "-DoutputAbsoluteArtifactFilename=true",
                    "-DoutputFile=" + str(output), "-DappendOutput=false"], check=True)
    dependencies = []
    for line in output.read_text(encoding="utf-8").splitlines():
        # dependency:list appends a JPMS module description after the artifact path.
        fields = line.strip().split(" -- ", 1)[0].split(":")
        if len(fields) not in (6, 7) or fields[-2] not in ("compile", "provided", "runtime", "system", "test"):
            continue
        if fields[2] != "jar" or fields[-2] not in ("compile", "provided", "runtime"):
            continue
        path = Path(fields[-1]).resolve(strict=True)
        dependencies.append(Dependency(fields[0], fields[1], path))
    if not dependencies:
        raise ValueError("Maven returned no JAR dependencies: " + str(output))
    return dependencies


def paper_pom(project: element_tree.Element, directory: Path, version: str,
              managed: bool = False) -> Path:
    """Resolve Paper independently, including descendants mediated out of our POM.

    The framework's dependencyManagement is copied only when managed is true (the
    modern exclusion graph). Old-server POMs never get it: a managed version would
    replace the old server's own transitive versions and hide old-version gaps."""
    directory.mkdir(parents=True, exist_ok=True)
    pom = element_tree.Element("project", xmlns=NAMESPACE["m"])
    for name, value in (("modelVersion", "4.0.0"), ("groupId", "linkage.audit"),
                        ("artifactId", "paper-api-classpath"), ("version", "1")):
        element_tree.SubElement(pom, name).text = value
    for name in ("properties", "repositories") + (("dependencyManagement",) if managed else ()):
        element = project.find("m:" + name, NAMESPACE)
        if element is not None:
            cloned = copy.deepcopy(element)
            # Maven's model reader expects unprefixed tags under the default
            # namespace, rather than ElementTree's generated ns0 prefixes.
            for child in cloned.iter():
                child.tag = child.tag.removeprefix("{" + NAMESPACE["m"] + "}")
            pom.append(cloned)
    dependency = element_tree.SubElement(element_tree.SubElement(pom, "dependencies"), "dependency")
    for name, value in (("groupId", PAPER[0]), ("artifactId", PAPER[1]), ("version", version)):
        element_tree.SubElement(dependency, name).text = value
    path = directory / "pom.xml"
    element_tree.ElementTree(pom).write(path, encoding="utf-8", xml_declaration=True)
    return path


def framework_resolution(framework: list[Dependency], modern: list[Dependency]) -> list[Path]:
    excluded = {dependency.key for dependency in modern} | {PAPER}
    print("Excluded modern Paper graph (all versions/classifiers): " +
          ", ".join(":".join(key) for key in sorted(excluded)), flush=True)
    added = []
    seen = set()
    for dependency in framework:
        if dependency.path in seen:
            continue
        seen.add(dependency.path)
        if dependency.key in excluded:
            print("Excluded " + str(dependency.path) + ": modern Paper dependency graph", flush=True)
            continue
        # Inspect entries, not the filename or Maven coordinates. Even a differently
        # named dependency must not supply server classes that mask the old API.
        with zipfile.ZipFile(dependency.path) as jar:
            forbidden = next((name for name in jar.namelist()
                              if name.startswith(SERVER_PREFIXES)
                              or (name.startswith("META-INF/versions/")
                                  and "/".join(name.split("/")[3:]).startswith(SERVER_PREFIXES))), None)
        if forbidden is not None:
            print("Excluded " + str(dependency.path) + ": forbidden server entry " + forbidden, flush=True)
            continue
        added.append(dependency.path)
    print("Framework resolution-only JARs (" + str(len(added)) + "):", flush=True)
    for path in added:
        print("  " + str(path), flush=True)
    return added


def main():
    pom_path = Path("pom.xml").resolve(strict=True)
    project = parse_xml(pom_path).getroot()
    version = required_text(project, "m:version")
    framework = Path(sys.argv[1] if len(sys.argv) == 2 else
                     "target/UltiTools-API-" + version + ".jar").resolve(strict=True)
    paper = next(dependency for dependency in project.findall("m:dependencies/m:dependency", NAMESPACE)
                 if (required_text(dependency, "m:groupId"), required_text(dependency, "m:artifactId")) == PAPER)
    root = Path("target/linkage-audit").resolve()
    modern_directory = root / "modern-paper"
    modern_pom = paper_pom(project, modern_directory, required_text(paper, "m:version"), managed=True)
    modern = resolve_dependencies(modern_pom, modern_directory, "runtime")
    # Maven's compile filter includes provided but not runtime; runtime includes
    # compile/runtime but not provided. The union excludes test/system dependencies.
    framework_dependencies = (resolve_dependencies(pom_path, root / "framework", "compile") +
                              resolve_dependencies(pom_path, root / "framework", "runtime"))
    supplemental = framework_resolution(framework_dependencies, modern)

    for paper_version in PAPER_VERSIONS:
        directory = root / paper_version
        old_pom = paper_pom(project, directory, paper_version)
        server = resolve_dependencies(old_pom, directory, "runtime")
        server.sort(key=lambda dependency: dependency.key != PAPER)
        # The old server's entire graph wins over every framework-only fallback.
        ordered = list(dict.fromkeys([dependency.path for dependency in server] + supplemental))
        classpath = os.pathsep.join(str(path) for path in ordered)
        (directory / "classpath.txt").write_text(classpath, encoding="utf-8")
        print("Auditing " + framework.name + " against Paper " + paper_version, flush=True)
        print("Resolution classpath (server graph first): " + classpath, flush=True)
        subprocess.run(["java", "-cp", str(framework) + os.pathsep + classpath,
                        "com.ultikits.ultitools.LinkageAudit", "--api", classpath,
                        "--own", OWN_PREFIX, str(framework)], check=True)
        print("Paper " + paper_version + ": 0 uncovered references", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
