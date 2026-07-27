#!/usr/bin/env python3
"""Assert a Lighthouse JSON report meets a per-category score threshold.

MILESTONES M6 asks for "Lighthouse >= 95 public". This is the assertion half of that: the CI job
runs `lighthouse --output=json` against each public page and hands the report here.

A file rather than an inline script in the workflow, deliberately. The obvious shape is a heredoc or
a `python3 -c` string inside the YAML step, and both are wrong in the same way: a YAML block scalar
gives every line the block's indentation, which Python rejects, and shell quoting inside it turns
every `"` into a `\\"` that Python then sees as a syntax error. Both failure modes were hit while
writing this. A file has neither, and can be run locally against a report exactly as CI runs it.

Usage:
    scripts/lighthouse-check.py REPORT.json PAGE_PATH [THRESHOLD]

Exit code 0 when every category is at or above the threshold, 1 otherwise. Failures are printed as
GitHub Actions error annotations, so they surface on the run's summary and not only in the log.
"""

import json
import sys

DEFAULT_THRESHOLD = 95


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print("usage: lighthouse-check.py REPORT.json PAGE_PATH [THRESHOLD]", file=sys.stderr)
        return 2

    report_path, page = argv[1], argv[2]
    threshold = int(argv[3]) if len(argv) > 3 else DEFAULT_THRESHOLD

    with open(report_path, encoding="utf-8") as handle:
        categories = json.load(handle)["categories"]

    if not categories:
        print(f"::error::{report_path} contains no Lighthouse categories at all.")
        return 1

    failed = False
    for category in categories.values():
        # A category Lighthouse could not score reports null; treating that as 0 is the safe
        # reading — an unscored accessibility audit is not a passing one.
        score = round((category["score"] or 0) * 100)
        title = category["title"]
        mark = "ok  " if score >= threshold else "FAIL"
        print(f"  {mark} {title:<16} {score}")
        if score < threshold:
            failed = True
            print(f"::error::Lighthouse {title} on {page} is {score}, below {threshold}.")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
