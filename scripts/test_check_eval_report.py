import json
import tempfile
import unittest
from pathlib import Path

from check_eval_report import check_directory


class ReportGateTest(unittest.TestCase):
    def test_missing_report_fails(self):
        with tempfile.TemporaryDirectory() as path:
            with self.assertRaisesRegex(ValueError, "no offline evaluation report"):
                check_directory(Path(path))

    def test_incomplete_report_fails(self):
        with tempfile.TemporaryDirectory() as path:
            (Path(path) / "report-1.json").write_text('{"total": 2}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "invalid report"):
                check_directory(Path(path))

    def test_both_tracks_and_case_counts_pass(self):
        fields = {
            "rawRatingAccuracyPercent": 50,
            "guardrailedRatingAccuracyPercent": 100,
            "correctedByGuardrail": 1,
            "regressedByGuardrail": 0,
            "evidenceAccuracyPercent": 100,
        }
        report = {
            "total": 2,
            "engines": {"mock": fields, "degraded": fields},
            "cases": {"mock": [{}, {}], "degraded": [{}, {}]},
        }
        with tempfile.TemporaryDirectory() as path:
            (Path(path) / "report-1.json").write_text(json.dumps(report), encoding="utf-8")
            self.assertEqual(check_directory(Path(path)), "report-1.json")


if __name__ == "__main__":
    unittest.main()
