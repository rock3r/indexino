# SPDX-License-Identifier: UEL-1.0
import hashlib
import tempfile
import unittest
from pathlib import Path

import harness


class HarnessTest(unittest.TestCase):
    def test_personal_repository_workflow_uses_unique_ephemeral_labels_not_organization_group(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/public-acceptance.yml").read_text()
        self.assertNotIn("group: public-acceptance-ephemeral", workflow)
        self.assertIn("indexino-public-${{ github.run_id }}-${{ github.run_attempt }}-${{ matrix.corpus }}", workflow)
        self.assertIn('INDEXINO_PUBLIC_CGROUP_PARENT', workflow)

    def test_phase_metrics_separate_combined_discovery_from_producer_work(self):
        events = [
            {"event": "refresh_started", "observedNanoTime": 10},
            {"event": "discovery_completed", "observedNanoTime": 37},
            {"event": "phase_started", "phase": "source-hash-preview", "observedNanoTime": 40},
            {"event": "phase_completed", "phase": "source-hash-preview", "observedNanoTime": 44},
            {"event": "phase_started", "phase": "kotlin-psi-symbols", "observedNanoTime": 46},
            {"event": "phase_completed", "phase": "kotlin-psi-symbols", "observedNanoTime": 61},
            {"event": "refresh_finished", "observedNanoTime": 70},
        ]
        result = harness.phase_metrics(events)
        self.assertEqual([27], result.get("preDiscoveryCompletedNanos"))
        self.assertEqual({"source-hash-preview": [4], "kotlin-psi-symbols": [15]}, result.get("phases"))
        self.assertIsNone(result.get("topologyOnlyNanos"))
        self.assertIsNone(result.get("captureAndHashNanos"))

    def test_phase_metrics_reject_incomplete_phase_instead_of_zero(self):
        with self.assertRaisesRegex(ValueError, "incomplete"):
            harness.phase_metrics([{"event": "phase_started", "phase": "java-source", "observedNanoTime": 1}])

    def test_inventory_rejects_equal_size_wrong_closure(self):
        with self.assertRaisesRegex(AssertionError, "missing.*Coordinator.kt"):
            harness.compare_inventory(["core/A.kt", "input/Coordinator.kt"],
                                      ["core/A.kt", "other/B.kt"])

    def test_inventory_rejects_duplicates(self):
        with self.assertRaisesRegex(AssertionError, "duplicate"):
            harness.compare_inventory(["a.kt"], ["a.kt", "a.kt"])

    def test_inventory_digest_is_order_independent(self):
        self.assertEqual(hashlib.sha256(b"a.kt\nz.java\n").hexdigest(),
                         harness.compare_inventory(["z.java", "a.kt"], ["a.kt", "z.java"]))

    def test_bazel_denominator_excludes_external_generated_and_non_source(self):
        rows = ["source file //a:B.kt", "source file @dep//:C.java",
                "generated file //a:Generated.kt", "source file //:README.md",
                "source file //a:res/x.xml"]
        paths, excluded = harness.bazel_inventory(rows)
        self.assertEqual(["a/B.kt", "a/res/x.xml"], paths)
        self.assertEqual({"external": 1, "generated": 1, "unsupported": 1}, excluded)

    def test_bazel_malformed_local_label_fails(self):
        with self.assertRaises(ValueError):
            harness.bazel_inventory(["source file //a:../../escape.kt"])

    def test_bazel_inventory_counts_directories_without_hiding_missing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            (workspace / "resources/nested").mkdir(parents=True)
            (workspace / "resources/icon.svg").write_text("<svg/>")
            paths, excluded = harness.bazel_inventory([
                "source file //resources:nested", "source file //resources:icon.svg",
                "source file //:Missing.kt",
            ], workspace)
            self.assertEqual(["Missing.kt", "resources/icon.svg"], paths)
            self.assertEqual(1, excluded.get("directory"))

    def test_bazel_inventory_includes_non_xml_resource_files(self):
        paths, excluded = harness.bazel_inventory([
            "source file //a:resources/icons/icon.svg",
            "source file //a:android-res/raw/data.bin",
            "source file //a:src/commonMain/composeResources/font/font.ttf",
            "source file //a:icons/unrelated.svg",
        ])
        self.assertEqual(["a/android-res/raw/data.bin", "a/resources/icons/icon.svg",
                          "a/src/commonMain/composeResources/font/font.ttf"], paths)
        self.assertEqual(1, excluded["unsupported"])

    def test_bazel_inventory_captures_only_metadata_reachable_from_queried_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            metadata = ["app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
                        "android/lib/build.gradle", "flat/build.gradle.kts",
                        "assets/AndroidManifest.xml", "unrelated/build.gradle.kts",
                        "config/build.gradle.kts"]
            for name in metadata:
                path = workspace / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("invented metadata")
            paths, _ = harness.bazel_inventory([
                "source file //app:src/main/kotlin/A.kt",
                "source file //app:src/main/AndroidManifest.xml",
                "source file //android/lib:src/main/java/B.java",
                "source file //flat:C.kt",
                "source file //assets:src/commonMain/composeResources/raw/data.bin",
                "source file //config:settings.xml",
                "source file //missing:src/main/kotlin/Missing.kt",
            ], workspace)
            expected = ["app/src/main/kotlin/A.kt", "app/src/main/AndroidManifest.xml",
                        "android/lib/src/main/java/B.java", "flat/C.kt",
                        "assets/src/commonMain/composeResources/raw/data.bin",
                        "config/settings.xml", "missing/src/main/kotlin/Missing.kt",
                        "app/build.gradle.kts", "android/lib/build.gradle",
                        "flat/build.gradle.kts", "assets/AndroidManifest.xml"]
            self.assertEqual(sorted(expected), paths)

    def test_bazel_inventory_rejects_metadata_symlinks_outside_corpus(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace = root / "corpus"
            (workspace / "app").mkdir(parents=True)
            outside = root / "outside.gradle.kts"
            outside.write_text("outside")
            (workspace / "app/build.gradle.kts").symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "metadata escapes"):
                harness.bazel_inventory(["source file //app:src/main/kotlin/A.kt"], workspace)

    def test_bazel_label_kind_preserves_spaces_in_supported_and_excluded_paths(self):
        try:
            paths, excluded = harness.bazel_inventory([
                "source file //java/java-impl:resources/fileTemplates/code/Catch Statement Body.java",
                "source file //a:resource with spaces.txt",
                "generated file //a:Generated File.kt",
            ])
        except ValueError as error:
            self.fail(f"valid Bazel filenames with spaces rejected: {error}")
        self.assertEqual(["java/java-impl/resources/fileTemplates/code/Catch Statement Body.java"], paths)
        self.assertEqual({"external": 0, "generated": 1, "unsupported": 1}, excluded)

    def test_percentiles_retain_samples_nearest_rank(self):
        result = harness.samples([100, 1, 2, 3, 4])
        self.assertEqual([100, 1, 2, 3, 4], result["raw"])
        self.assertEqual(3, result["p50"])
        self.assertEqual(100, result["p95"])

    def test_mac_is_not_ready_even_with_memory(self):
        self.assertIn("containment", " ".join(harness.readiness("Darwin", 64 << 30,
                                                              400 << 30, None)))

    def test_low_capacity_is_not_ready(self):
        reasons = harness.readiness("Linux", 12 << 30, 10 << 30, None)
        self.assertTrue(any("memory" in x for x in reasons))
        self.assertTrue(any("disk" in x for x in reasons))

    def test_serial_profile_accepts_18_gib_available_but_not_one_byte_less(self):
        with tempfile.TemporaryDirectory() as temporary:
            group = Path(temporary)
            for name, value in {"cgroup.kill": "", "pids.max": "max", "memory.max": "max"}.items():
                (group / name).write_text(value)
            self.assertEqual([], harness.readiness("Linux", 18 << 30, 200 << 30, group))
            reasons = harness.readiness("Linux", (18 << 30) - 1, 200 << 30, group)
            self.assertTrue(any("memory" in reason for reason in reasons), reasons)

    def test_parent_cgroup_limit_cannot_be_hidden_by_host_memory(self):
        with tempfile.TemporaryDirectory() as temporary:
            group = Path(temporary)
            for name, value in {"cgroup.kill": "", "pids.max": "max",
                                "memory.max": str(16 << 30), "memory.current": str(4 << 30)}.items():
                (group / name).write_text(value)
            reasons = harness.readiness("Linux", 64 << 30, 200 << 30, group)
            self.assertTrue(any("memory" in x for x in reasons), reasons)

    def test_artifact_digest_mismatch_prevents_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            artifact = Path(tmp) / "driver.zip"
            artifact.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "digest"):
                harness.verify_artifact(artifact, "0" * 64)


if __name__ == "__main__":
    unittest.main()
