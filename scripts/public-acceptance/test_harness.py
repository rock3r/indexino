# SPDX-License-Identifier: UEL-1.0
import hashlib
import tempfile
import unittest
from pathlib import Path

import harness


class HarnessTest(unittest.TestCase):
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

    def test_artifact_digest_mismatch_prevents_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            artifact = Path(tmp) / "driver.zip"
            artifact.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "digest"):
                harness.verify_artifact(artifact, "0" * 64)


if __name__ == "__main__":
    unittest.main()
