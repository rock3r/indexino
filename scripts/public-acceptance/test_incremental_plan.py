# SPDX-License-Identifier: UEL-1.0
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import incremental_plan


class IncrementalPlanTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def source(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return relative

    def test_exact_reproducible_groups_use_closest_bazel_module_and_both_languages(self):
        inventory = [self.source("wide/src/Z.java", "/* package false.name; */\npackage real.java; class Z {}")]
        self.source("wide/BUILD.bazel", "")
        for number in range(9):
            inventory.append(self.source(
                f"wide/src/K{number}.kt",
                "// package commented.out\n/* another package fake; */\npackage real.kotlin\nclass K",
            ))
        inventory += [
            self.source("other/BUILD", ""),
            self.source("other/src/A.java", "package other; class A {}"),
            self.source("lonely/X.kt", "class X"),
            self.source("wide/src/package-info.java", "package real.java;"),
            self.source("wide/src/data.xml", "<x/>")
        ]

        first = incremental_plan.make_plan(inventory, self.root)
        second = incremental_plan.make_plan(list(reversed(inventory)), self.root)
        self.assertEqual(first, second)
        self.assertEqual({"schema": 1, "buildSystem": "bazel", "target": "//:main"},
                         {key: first[key] for key in ("schema", "buildSystem", "target")})
        groups = {group["id"]: group["files"] for group in first["groups"]}
        self.assertEqual(1, len(groups["small1"]))
        self.assertEqual(10, len(groups["medium"]))
        self.assertEqual(11, len(groups["large"]))
        medium = [first["files"][index] for index in groups["medium"]]
        self.assertEqual({"wide"}, {item["module"] for item in medium})
        self.assertEqual({"java", "kt"}, {Path(item["path"]).suffix[1:] for item in medium})
        java = next(item for item in first["files"] if item["path"].endswith("Z.java"))
        self.assertEqual("real.java", java["package"])
        self.assertEqual(hashlib.sha256((self.root / java["path"]).read_bytes()).hexdigest(),
                         java["sha256"])
        expected_inventory_hash = hashlib.sha256(
            ("\n".join(sorted(inventory)) + "\n").encode()).hexdigest()
        self.assertEqual(expected_inventory_hash, first["provenance"]["inventorySha256"])
        self.assertEqual(["other", "wide"], first["report"]["eligibleModules"])
        self.assertEqual({"noBazelModule": 1, "specialSource": 1, "unsupportedExtension": 2},
                         first["report"]["excluded"])
        self.assertEqual({"small1": 1, "medium": 10, "large": 11},
                         first["report"]["exactCoverage"])

    def test_java_shaped_resources_are_not_code_mutation_targets(self):
        self.source("m/BUILD", "")
        code = [self.source(f"m/src/C{number}.java", "package p; class C {}")
                for number in range(10)]
        resource = self.source("m/resources/A.java", "package data; class A {}")
        plan = incremental_plan.make_plan(code + [resource], self.root, code_inventory=code)
        self.assertEqual(set(code), {entry["path"] for entry in plan["files"]})
        self.assertEqual(1, plan["report"]["excluded"]["nonCode"])
        self.assertEqual(10, plan["provenance"]["codeInventoryCount"])

    def test_bazel_graph_follows_code_groups_and_aliases_but_not_resource_groups(self):
        graph = '''<query>
          <rule name="//:main" class="jvm_library">
            <list name="srcs"><label value="//m:code"/><label value="//m:Direct.java"/>
              <label value="//m:generated.java"/><label value="@external//:External.java"/></list>
            <list name="resources"><label value="//m:data"/></list>
          </rule>
          <rule name="//m:code" class="filegroup"><list name="srcs">
            <label value="//m:alias"/><label value="//m:code"/></list></rule>
          <rule name="//m:alias" class="alias"><label name="actual" value="//m:Aliased.kt"/></rule>
          <rule name="//m:data" class="filegroup"><list name="srcs">
            <label value="//m:Resource.java"/></list></rule>
        </query>'''
        inventory = ["m/Direct.java", "m/Aliased.kt", "m/Resource.java"]
        self.assertEqual(["m/Aliased.kt", "m/Direct.java"],
                         incremental_plan.code_inventory_from_bazel_xml(graph, inventory))

    def test_medium_reports_insufficient_without_silently_changing_requested_size(self):
        inventory = [self.source("m/BUILD", ""), self.source("m/A.kt", "package p\nclass A")]
        with self.assertRaisesRegex(ValueError, "no single eligible module has 10 files"):
            incremental_plan.make_plan(inventory, self.root)

    def test_rejects_windows_absolute_and_noncanonical_paths_before_inventory_selection(self):
        for path in ("C:/outside/A.kt", "m//A.kt", "m/./A.kt"):
            with self.subTest(path=path):
                with self.assertRaisesRegex(ValueError, "unsafe|absolute"):
                    incremental_plan.make_plan([path], self.root)

    def test_rejects_duplicates_absolute_traversal_and_symlink_escape(self):
        self.source("m/BUILD", "")
        source = self.source("m/A.kt", "class A")
        for inventory, message in [([source, source], "duplicate"),
                                   (["/tmp/A.kt"], "absolute"),
                                   (["m/../A.kt"], "unsafe")]:
            with self.subTest(inventory=inventory):
                with self.assertRaisesRegex(ValueError, message):
                    incremental_plan.make_plan(inventory, self.root)
        with tempfile.TemporaryDirectory() as other:
            outside = Path(other) / "outside.kt"
            outside.write_text("class Outside")
            (self.root / "m/link.kt").symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "symlink|escape"):
                incremental_plan.make_plan(["m/link.kt"], self.root)

    def test_cli_reads_json_list_and_writes_plan(self):
        self.source("m/BUILD", "")
        inventory = [self.source(f"m/A{number}.java", "package p; class A {}")
                     for number in range(10)]
        inventory_path, output = self.root / "inventory.json", self.root / "plan.json"
        inventory_path.write_text(json.dumps(inventory))
        graph = self.root / "rules.xml"
        labels = "".join(f'<label value="//m:A{number}.java"/>' for number in range(10))
        graph.write_text('<query><rule name="//:main" class="jvm_library">'
                         f'<list name="srcs">{labels}</list></rule></query>')
        self.assertEqual(0, incremental_plan.main([
            "--inventory", str(inventory_path), "--checkout-root", str(self.root),
            "--bazel-rules-xml", str(graph),
            "--output", str(output)]))
        self.assertEqual("m/A0.java", json.loads(output.read_text())["files"][0]["path"])


if __name__ == "__main__":
    unittest.main()
