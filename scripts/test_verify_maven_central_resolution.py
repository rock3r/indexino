import io
import unittest
import urllib.error
from unittest.mock import patch

import verify_maven_central_resolution as gate


class FakeResponse:
    def __init__(self, status):
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class FakeOpener:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def open(self, request, timeout):
        self.calls.append(request.full_url)
        status = self.responses.pop(0)
        if status != 200:
            raise urllib.error.HTTPError(request.full_url, status, "no", hdrs=None, fp=io.BytesIO())
        return FakeResponse(status)


class ResolutionGateTest(unittest.TestCase):
    def test_timeout_reports_pending_coordinates_and_does_not_claim_success(self):
        opener = FakeOpener([404] * len(gate.ARTIFACTS))
        with self.assertRaises(TimeoutError) as raised:
            gate.wait_for_resolution(
                "https://repo1.maven.org/maven2",
                "9.9.9",
                timeout_seconds=0,
                interval_seconds=30,
                clock=lambda: 0,
                sleeper=lambda _: self.fail("pending resolution must not sleep after the deadline"),
                opener=opener,
            )
        self.assertIn("indexino-script-host", str(raised.exception))
        self.assertEqual(len(opener.calls), len(gate.ARTIFACTS))

    def test_validation_failure_is_not_treated_as_resolution(self):
        opener = FakeOpener([500] + [200] * (len(gate.ARTIFACTS) - 1))
        statuses = gate.resolve("https://repo.example/maven2", "1.2.3", opener)
        self.assertEqual(statuses["indexino"], 500)
        self.assertFalse(all(status == 200 for status in statuses.values()))

    def test_success_requires_every_expected_coordinate(self):
        pending = [404] * len(gate.ARTIFACTS)
        ready = [200] * len(gate.ARTIFACTS)
        opener = FakeOpener(pending + ready)
        sleeps = []
        now = {"value": 0}

        def clock():
            return now["value"]

        def sleeper(seconds):
            sleeps.append(seconds)
            now["value"] += seconds

        statuses = gate.wait_for_resolution(
            "https://repo1.maven.org/maven2",
            "0.3.0",
            timeout_seconds=60,
            interval_seconds=15,
            clock=clock,
            sleeper=sleeper,
            opener=opener,
        )
        self.assertEqual(set(statuses), set(gate.ARTIFACTS))
        self.assertTrue(all(status == 200 for status in statuses.values()))
        self.assertEqual(sleeps, [15])
        self.assertTrue(opener.calls[0].endswith("/dev/sebastiano/indexino/indexino/0.3.0/indexino-0.3.0.pom"))


if __name__ == "__main__":
    unittest.main()
