# SPDX-License-Identifier: UEL-1.0
"""Create a deterministic controlled-mutation plan from an independent Bazel inventory."""

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath, PureWindowsPath


MEDIUM_SIZE = 10
SOURCE_SUFFIXES = {".java", ".kt"}
SPECIAL_NAMES = {"module-info.java", "package-info.java", "package-info.kt"}


def code_inventory_from_bazel_xml(xml_text, inventory):
    """Walk Bazel's rule graph independently of Indexino's role-query implementation."""
    _validate_inventory(inventory)
    rules = {rule.attrib["name"]: rule for rule in ET.fromstring(xml_text).findall("rule")}

    def attributes(rule, name):
        for attribute in rule:
            if attribute.get("name") == name:
                if attribute.tag == "label":
                    yield attribute.attrib["value"]
                else:
                    yield from (label.attrib["value"] for label in attribute.findall("label"))

    pending = [label for rule in rules.values()
               if rule.get("class") not in ("filegroup", "alias")
               for label in attributes(rule, "srcs")]
    if "//:main" in rules:
        pending.extend(attributes(rules["//:main"], "srcs"))
        pending.extend(attributes(rules["//:main"], "actual"))
    visited, sources = set(), set()
    while pending:
        label = pending.pop()
        if label in visited:
            continue
        visited.add(label)
        rule = rules.get(label)
        if rule is not None:
            if rule.get("class") in ("filegroup", "alias"):
                pending.extend(attributes(rule, "srcs"))
                pending.extend(attributes(rule, "actual"))
        elif label.startswith("//") and label.count(":") == 1:
            package, name = label[2:].split(":")
            sources.add(f"{package}/{name}" if package else name)
    # The independent label-kind inventory excludes generated outputs and external repositories.
    return sorted(sources.intersection(inventory))


def _without_comments(text):
    """Replace comments with whitespace while preserving strings and line boundaries."""
    result, index, state = [], 0, "code"
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "code" and char == "/" and following == "/":
            result.extend("  ")
            index += 2
            state = "line"
        elif state == "code" and char == "/" and following == "*":
            result.extend("  ")
            index += 2
            state = "block"
        elif state == "line":
            result.append("\n" if char == "\n" else " ")
            if char == "\n":
                state = "code"
            index += 1
        elif state == "block":
            if char == "*" and following == "/":
                result.extend("  ")
                index += 2
                state = "code"
            else:
                result.append("\n" if char == "\n" else " ")
                index += 1
        elif state == "code" and char in ('"', "'"):
            result.append(char)
            index += 1
            state = char
        elif state in ('"', "'"):
            result.append(char)
            index += 1
            if char == "\\" and index < len(text):
                result.append(text[index])
                index += 1
            elif char == state:
                state = "code"
        else:
            result.append(char)
            index += 1
    return "".join(result)


def _package(text):
    match = re.search(r"(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*)\s*[;\n]",
                      _without_comments(text))
    return re.sub(r"\s+", "", match.group(1)) if match else ""


def _module_for(path, root):
    directory = path.parent
    while directory == root or directory.is_relative_to(root):
        for build_name in ("BUILD.bazel", "BUILD"):
            build = directory / build_name
            if build.exists():
                if build.is_symlink() or not build.is_file() or not build.resolve().is_relative_to(root):
                    raise ValueError(f"Bazel module marker escapes checkout: {build}")
                relative = directory.relative_to(root).as_posix()
                return "." if relative == "." else relative
        if directory == root:
            break
        directory = directory.parent
    return None


def _validate_inventory(inventory):
    if not isinstance(inventory, list) or not all(isinstance(item, str) for item in inventory):
        raise ValueError("inventory must be a JSON list of relative path strings")
    if len(inventory) != len(set(inventory)):
        raise ValueError("duplicate inventory entries")
    for item in inventory:
        path = PurePosixPath(item)
        if path.is_absolute() or PureWindowsPath(item).drive:
            raise ValueError(f"absolute inventory path: {item}")
        if not item or "\\" in item or any(part in ("", ".", "..") for part in item.split("/")):
            raise ValueError(f"unsafe inventory path: {item}")


def _medium_sources(by_module):
    candidates = [(module, sources) for module, sources in by_module.items()
                  if len(sources) >= MEDIUM_SIZE]
    if not candidates:
        return []
    # Prefer a module that can exercise both parsers, then lexical module identity.
    module, sources = min(candidates, key=lambda item: (
        -len({source["path"].rsplit(".", 1)[-1] for source in item[1]}), item[0]))
    languages = defaultdict(list)
    for source in sources:
        languages[Path(source["path"]).suffix].append(source)
    selected = []
    for suffix in sorted(languages):
        selected.append(languages[suffix][0])
    selected.extend(source for source in sources if source not in selected)
    return selected[:MEDIUM_SIZE]


def make_plan(inventory, checkout_root, code_inventory=None):
    _validate_inventory(inventory)
    code_inventory = inventory if code_inventory is None else code_inventory
    _validate_inventory(code_inventory)
    if not set(code_inventory).issubset(inventory):
        raise ValueError("code inventory must be a subset of the source inventory")
    code_sources = set(code_inventory)
    root = Path(checkout_root).resolve(strict=True)
    excluded = Counter()
    eligible = []
    for relative in sorted(inventory):
        suffix = PurePosixPath(relative).suffix
        if suffix not in SOURCE_SUFFIXES:
            excluded["unsupportedExtension"] += 1
            continue
        if PurePosixPath(relative).name in SPECIAL_NAMES:
            excluded["specialSource"] += 1
            continue
        if relative not in code_sources:
            excluded["nonCode"] += 1
            continue
        path = root / relative
        if path.is_symlink():
            raise ValueError(f"source symlink is unsupported: {relative}")
        if not path.is_file():
            excluded["missingOrNotRegular"] += 1
            continue
        resolved = path.resolve()
        if not resolved.is_relative_to(root):
            raise ValueError(f"source escapes checkout: {relative}")
        module = _module_for(resolved, root)
        if module is None:
            excluded["noBazelModule"] += 1
            continue
        original = path.read_bytes()
        try:
            text = original.decode("utf-8")
        except UnicodeDecodeError:
            excluded["invalidUtf8"] += 1
            continue
        if "IndexinoBenchmark" in text:
            excluded["benchmarkMarkerCollision"] += 1
            continue
        eligible.append({"path": relative, "module": module, "package": _package(text),
                         "sha256": hashlib.sha256(original).hexdigest()})

    by_module = defaultdict(list)
    for source in eligible:
        by_module[source["module"]].append(source)
    medium = _medium_sources(by_module)
    if not medium:
        raise ValueError("no single eligible module has 10 files")
    representatives = [sources[0] for _, sources in sorted(by_module.items())]
    large = list({source["path"]: source for source in medium + representatives}.values())
    small = eligible[:1]
    selected = sorted({source["path"]: source for source in small + large}.values(),
                      key=lambda source: source["path"])
    indices = {source["path"]: index for index, source in enumerate(selected)}
    groups = [
        {"id": "small1", "files": [indices[item["path"]] for item in small]},
        {"id": "medium", "files": [indices[item["path"]] for item in medium]},
        {"id": "large", "files": [indices[item["path"]] for item in large]},
    ]
    coverage = {group["id"]: len(group["files"]) for group in groups}
    return {
        "schema": 1, "buildSystem": "bazel", "target": "//:main",
        "files": selected, "groups": groups,
        "provenance": {
            "inventorySha256": hashlib.sha256(
                ("\n".join(sorted(inventory)) + "\n").encode("utf-8")).hexdigest(),
            "inventoryCount": len(inventory),
            "codeInventoryCount": len(code_inventory),
            "codeInventorySha256": hashlib.sha256(
                ("\n".join(sorted(code_inventory)) + "\n").encode("utf-8")).hexdigest(),
        },
        "report": {
            "eligibleModules": sorted(by_module), "eligibleSourceCount": len(eligible),
            "excluded": dict(sorted(excluded.items())), "exactCoverage": coverage,
            "mediumStatus": "exactly 10 files from one module",
            "workloadInterpretation": (
                "small1 mutates one source; medium mutates exactly 10 sources in one Bazel "
                "module; large mutates medium plus one representative from every eligible module"
            ),
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, type=Path,
                        help="JSON list produced from the independent Bazel inventory")
    parser.add_argument("--checkout-root", required=True, type=Path)
    parser.add_argument("--bazel-rules-xml", required=True, type=Path,
                        help="independent Bazel query kind('rule', deps(//:main)) --output=xml")
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args(argv)
    inventory = json.loads(arguments.inventory.read_text(encoding="utf-8"))
    xml_text = arguments.bazel_rules_xml.read_text(encoding="utf-8")
    code_inventory = code_inventory_from_bazel_xml(xml_text, inventory)
    plan = make_plan(inventory, arguments.checkout_root, code_inventory)
    plan["provenance"]["bazelRulesXmlSha256"] = hashlib.sha256(
        arguments.bazel_rules_xml.read_bytes()).hexdigest()
    arguments.output.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
