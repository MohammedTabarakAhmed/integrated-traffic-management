"""Tests for the ledger verifier (DLV-001). Run: python -m unittest discover -s tools/traceability"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import verify_ledger as v  # noqa: E402

HEADER_L = ("# L\n\nTotal numbered requirements: **{t}** — Must: {m}, Should: {s}, Could: {c}.\n\n"
            "| Status | ID | Priority | Requirement | Implementation | Test | Evidence/notes |\n|---|---|---|---|---|---|---|\n")
HEADER_T = "# T\n\n| Status | ID | Priority | Area | Implementation | Test | Evidence | Notes |\n|---|---|---|---|---|---|---|---|\n"


def lrow(status, rid, pri, impl="—", test="—", ev="—"):
    return f"| {status} | `{rid}` | {pri} | Requirement text for {rid}. | {impl} | {test} | {ev} |\n"


def trow(status, rid, pri):
    return f"| {status} | `{rid}` | {pri} | Area | — | — | — | — |\n"


class VerifyLedgerTest(unittest.TestCase):
    def make_repo(self, ledger_rows, trace_rows, totals=None, files=None):
        d = Path(tempfile.mkdtemp())
        (d / "docs").mkdir()
        if totals is None:
            pr = [r.split("|")[3].strip() for r in ledger_rows]
            totals = (len(ledger_rows), pr.count("M"), pr.count("S"), pr.count("C"))
        t, m, s, c = totals
        (d / "docs" / "BRD_REQUIREMENTS.md").write_text(HEADER_L.format(t=t, m=m, s=s, c=c) + "".join(ledger_rows), encoding="utf-8")
        (d / "docs" / "TRACEABILITY.md").write_text(HEADER_T + "".join(trace_rows), encoding="utf-8")
        for rel, content in (files or {}).items():
            f = d / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(content, encoding="utf-8")
        return d

    def test_DLV_001_consistent_ledger_passes(self):
        repo = self.make_repo([lrow("[ ]", "CC-001", "M"), lrow("[ ]", "CC-002", "S")],
                              [trow("[ ]", "CC-001", "M"), trow("[ ]", "CC-002", "S")])
        errors, stats = v.verify(repo)
        self.assertEqual(errors, [])
        self.assertEqual(stats["total"], 2)

    def test_duplicate_and_malformed_ids_fail(self):
        repo = self.make_repo([lrow("[ ]", "CC-001", "M"), lrow("[ ]", "CC-001", "M"), lrow("[ ]", "CC-1", "M")],
                              [trow("[ ]", "CC-001", "M"), trow("[ ]", "CC-001", "M"), trow("[ ]", "CC-1", "M")])
        errors, _ = v.verify(repo)
        self.assertTrue(any("duplicate ID CC-001" in e for e in errors))
        self.assertTrue(any("malformed ID 'CC-1'" in e for e in errors))

    def test_header_totals_mismatch_fails(self):
        repo = self.make_repo([lrow("[ ]", "CC-001", "M")], [trow("[ ]", "CC-001", "M")], totals=(438, 374, 63, 1))
        errors, _ = v.verify(repo)
        self.assertTrue(any("totals mismatch" in e for e in errors))

    def test_traceability_drift_fails(self):
        repo = self.make_repo([lrow("[~]", "CC-001", "M"), lrow("[ ]", "CC-002", "M")],
                              [trow("[ ]", "CC-001", "S")])
        errors, _ = v.verify(repo)
        self.assertTrue(any("missing from TRACEABILITY" in e for e in errors))
        self.assertTrue(any("CC-001 priority" in e for e in errors))
        self.assertTrue(any("CC-001 status" in e for e in errors))

    def test_done_without_evidence_or_tagged_test_fails(self):
        repo = self.make_repo([lrow("[x]", "CC-001", "M")], [trow("[x]", "CC-001", "M")])
        errors, _ = v.verify(repo)
        self.assertTrue(any("implementation is empty" in e for e in errors))
        self.assertTrue(any("no test in the repo is tagged" in e for e in errors))

    def test_done_with_evidence_and_tagged_java_test_passes(self):
        java = "class T { @Test @" + 'Tag("CC-001") void rbac() {} }'  # split so this file is not itself tagged
        repo = self.make_repo(
            [lrow("[x]", "CC-001", "M", impl="services/iam", test="RbacTest", ev="CI run 42")],
            [trow("[x]", "CC-001", "M")],
            files={"services/iam/src/test/java/RbacTest.java": java})
        errors, stats = v.verify(repo)
        self.assertEqual(errors, [])
        self.assertEqual(stats["tagged_requirements"], 1)

    def test_incidental_id_mention_is_not_test_evidence(self):
        repo = self.make_repo(
            [lrow("[x]", "CC-001", "M", impl="x", test="y", ev="z")],
            [trow("[x]", "CC-001", "M")],
            files={"tests/test_other.py": "# mentions CC-001 in passing\n",
                   "tests/test_real.py": "# @" + "req CC-002, CC-003\n"})
        errors, _ = v.verify(repo)
        self.assertTrue(any("CC-001 marked [x] but no test" in e for e in errors))
        self.assertEqual(v.tagged_ids(repo), {"CC-002", "CC-003"})

    def test_invalid_status_fails(self):
        repo = self.make_repo([lrow("[y]", "CC-001", "M")], [trow("[y]", "CC-001", "M")])
        errors, _ = v.verify(repo)
        self.assertTrue(any("invalid status" in e for e in errors))


class RealRepoTest(unittest.TestCase):
    """The actual project ledger must always verify (438 requirements, 374/63/1)."""

    # @req DLV-001
    def test_DLV_001_project_ledger_is_consistent(self):
        repo = Path(__file__).resolve().parents[2]
        errors, stats = v.verify(repo)
        self.assertEqual(errors, [])
        self.assertEqual(stats["total"], 438)
        self.assertEqual(stats["priority"], {"M": 374, "S": 63, "C": 1})


if __name__ == "__main__":
    unittest.main()
