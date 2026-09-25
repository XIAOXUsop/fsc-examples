"""Fail CI when the offline evaluation report is missing or incomplete."""

import json
import sys
from pathlib import Path


def validate_report(data):
    total = data.get("total")
    if isinstance(total, bool) or not isinstance(total, int) or total <= 0:
        raise ValueError("total must be a positive integer")

    engines = data.get("engines")
    cases = data.get("cases")
    if not isinstance(engines, dict) or not isinstance(cases, dict):
        raise ValueError("engines and cases must be objects")

    for name in ("mock", "degraded"):
        score = engines.get(name)
        rows = cases.get(name)
        if not isinstance(score, dict) or not isinstance(rows, list) or len(rows) != total:
            raise ValueError(f"{name} results must contain {total} case rows")
        for field in (
            "rawRatingAccuracyPercent",
            "guardrailedRatingAccuracyPercent",
            "correctedByGuardrail",
            "regressedByGuardrail",
            "evidenceAccuracyPercent",
        ):
            value = score.get(field)
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError(f"{name}.{field} must be numeric")


def check_directory(directory):
    reports = sorted(directory.glob("report-*.json"))
    if not reports:
        raise ValueError(f"no offline evaluation report in {directory}")
    # CI runs `clean test`, so there is one report. Local runs may retain old
    # reports with an earlier schema; validate the newest generated result.
    report = reports[-1]
    try:
        data = json.loads(report.read_text(encoding="utf-8"))
        validate_report(data)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise ValueError(f"invalid report {report}: {error}") from error
    return report.name


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: check_eval_report.py <report-directory>")
    try:
        report_name = check_directory(Path(sys.argv[1]))
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(f"Validated offline evaluation report: {report_name}")
