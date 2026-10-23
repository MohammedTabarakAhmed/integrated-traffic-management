#!/usr/bin/env python3
"""ITMS requirement-ledger verifier (DLV-001, the project guide Â§8-9).

Checks:
  1. docs/brief/01_PROJECT_REQUIREMENTS.md (ledger part): IDs well-formed, unique, totals match the header line.
  2. docs/brief/06_STATUS_BRIEF.md (traceability part) mirrors the ledger (same IDs, order, priority, status).
  3. Status markers are valid.
  4. A requirement marked [x] has implementation, test and evidence filled in, and at
     least one test in the repository is tagged with its ID (e.g. @Tag("VF-032")).
  5. Optional --docx: ledger IDs, priorities and wording match the original BRD.

Exit code 0 = OK, 1 = violations found. Standard library only.
"""
from __future__ import annotations

import argparse
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

ID_RE = re.compile(r"^[A-Z]{2,4}-\d{3}$")
VALID_STATUS = {"[ ]", "[~]", "[x]", "[!]", "[?]"}
VALID_PRIORITY = {"M", "S", "C", "W"}
EMPTY = {"", "â€”", "-"}
TOTALS_RE = re.compile(r"Total numbered requirements: \*\*(\d+)\*\* â€” Must: (\d+), Should: (\d+), Could: (\d+)")
TEST_GLOBS = ("**/src/test/**/*.java", "tests/**/*.java", "tests/**/*.py", "tools/**/test_*.py", "**/*.spec.ts", "**/*.test.ts")


@dataclass
class Row:
    line: int
    status: str
    req_id: str
    priority: str
    cells: list[str]


def parse_table(path: Path) -> list[Row]:
    rows = []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.startswith("| ["):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        rows.append(Row(n, cells[0], cells[1].strip("`"), cells[2], cells))
    return rows


# Explicit markers only, so incidental mentions of an ID never count as test evidence:
#   Java:        @Tag("VF-032") or @Req("VF-032")
#   Python/TS:   a comment "@req VF-032" (several IDs may be comma-separated)
TAG_RE = re.compile(r'@(?:Tag|Req)\("([A-Z]{2,4}-\d{3})"\)')
COMMENT_TAG_RE = re.compile(r"@req\s+([A-Z]{2,4}-\d{3}(?:\s*,\s*[A-Z]{2,4}-\d{3})*)")


def tagged_ids(repo: Path) -> set[str]:
    found: set[str] = set()
    for glob in TEST_GLOBS:
        for f in repo.glob(glob):
            if "node_modules" in f.parts or "target" in f.parts:
                continue
            text = f.read_text(encoding="utf-8", errors="ignore")
            found.update(TAG_RE.findall(text))
            for group in COMMENT_TAG_RE.findall(text):
                found.update(i.strip() for i in group.split(","))
    return found


def docx_requirements(docx: Path) -> dict[str, tuple[str, str]]:
    ns = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    root = ET.fromstring(zipfile.ZipFile(docx).read("word/document.xml"))
    reqs: dict[str, tuple[str, str]] = {}
    for tr in root.iter(f"{ns}tr"):
        cells = ["".join(t.text or "" for t in tc.iter(f"{ns}t")).strip() for tc in tr.findall(f"{ns}tc")]
        if cells and ID_RE.match(cells[0]):
            reqs[cells[0]] = (cells[-1], " / ".join(cells[1:-1]))
    return reqs


def verify(repo: Path, docx: Path | None = None) -> tuple[list[str], dict]:
    errors: list[str] = []
    ledger_path = repo / "docs" / "brief" / "01_PROJECT_REQUIREMENTS.md"
    trace_path = repo / "docs" / "brief" / "06_STATUS_BRIEF.md"
    ledger = parse_table(ledger_path)
    trace = parse_table(trace_path)

    # 1. Ledger integrity
    ids = [r.req_id for r in ledger]
    for r in ledger:
        if not ID_RE.match(r.req_id):
            errors.append(f"ledger:{r.line} malformed ID {r.req_id!r}")
        if r.status not in VALID_STATUS:
            errors.append(f"ledger:{r.line} {r.req_id} invalid status {r.status!r}")
        if r.priority not in VALID_PRIORITY:
            errors.append(f"ledger:{r.line} {r.req_id} invalid priority {r.priority!r}")
        if len(r.cells) != 7:
            errors.append(f"ledger:{r.line} {r.req_id} expected 7 columns, got {len(r.cells)}")
    for rid, n in Counter(ids).items():
        if n > 1:
            errors.append(f"ledger duplicate ID {rid} ({n}x)")
    m = TOTALS_RE.search(ledger_path.read_text(encoding="utf-8"))
    pri = Counter(r.priority for r in ledger)
    if not m:
        errors.append("ledger header totals line not found")
    else:
        total, must, should, could = map(int, m.groups())
        if (len(ledger), pri["M"], pri["S"], pri["C"]) != (total, must, should, could):
            errors.append(
                f"ledger totals mismatch: header {total}/{must}/{should}/{could}, "
                f"rows {len(ledger)}/{pri['M']}/{pri['S']}/{pri['C']}")

    # 2. Traceability mirrors ledger
    tids = [r.req_id for r in trace]
    if set(ids) - set(tids):
        errors.append(f"missing from TRACEABILITY: {sorted(set(ids) - set(tids))}")
    if set(tids) - set(ids):
        errors.append(f"in TRACEABILITY but not ledger: {sorted(set(tids) - set(ids))}")
    if ids != tids and set(ids) == set(tids):
        errors.append("TRACEABILITY row order differs from ledger")
    tmap = {r.req_id: r for r in trace}
    for r in ledger:
        t = tmap.get(r.req_id)
        if not t:
            continue
        if t.priority != r.priority:
            errors.append(f"{r.req_id} priority ledger={r.priority} trace={t.priority}")
        if t.status != r.status:
            errors.append(f"{r.req_id} status ledger={r.status} trace={t.status}")

    # 4. [x] needs evidence and a tagged test
    tagged = tagged_ids(repo)
    for r in ledger:
        if r.status != "[x]":
            continue
        impl, test, evidence = r.cells[4], r.cells[5], r.cells[6]
        for name, val in (("implementation", impl), ("test", test), ("evidence", evidence)):
            if val in EMPTY:
                errors.append(f"{r.req_id} marked [x] but {name} is empty")
        if r.req_id not in tagged:
            errors.append(f"{r.req_id} marked [x] but no test in the repo is tagged with it")

    # 5. Optional source check
    if docx:
        src = docx_requirements(docx)
        led = {r.req_id: (r.priority, r.cells[3]) for r in ledger}
        if set(src) != set(led):
            errors.append(f"docx/ledger ID sets differ: only docx={sorted(set(src)-set(led))}, only ledger={sorted(set(led)-set(src))}")
        for rid in sorted(set(src) & set(led)):
            if src[rid][0] != led[rid][0]:
                errors.append(f"{rid} priority docx={src[rid][0]} ledger={led[rid][0]}")
            if src[rid][1].replace(" ", "") != led[rid][1].replace(" ", ""):
                errors.append(f"{rid} wording differs from docx")

    stats = {
        "total": len(ledger),
        "priority": dict(pri),
        "status": dict(Counter(r.status for r in ledger)),
        "tagged_requirements": len(tagged & set(ids)),
    }
    return errors, stats


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--docx", type=Path, help="optional original BRD .docx to verify wording against")
    args = p.parse_args(argv)
    errors, stats = verify(args.repo, args.docx)
    print(f"Requirements: {stats['total']}  priority={stats['priority']}  status={stats['status']}  "
          f"with tagged tests={stats['tagged_requirements']}")
    for e in errors:
        print(f"ERROR: {e}")
    print("OK" if not errors else f"FAILED: {len(errors)} problem(s)")
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
