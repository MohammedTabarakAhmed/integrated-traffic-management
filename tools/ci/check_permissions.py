#!/usr/bin/env python3
"""Permission-catalogue consistency check (CC-001, CC-005, ADR-022).

Roles in iam-service may only contain catalogue permissions, and every service enforces the permissions declared in
its OpenAPI. This check keeps the three in step:
  1. every hasAuthority('<perm>') in a service's Java code is declared as x-itms-permission in that service's OpenAPI;
  2. every x-itms-permission (except the pseudo-value "authenticated") is in the iam-service catalogue migrations,
     registered under the declaring service's name.

Exit code 0 = OK, 1 = violations found. Standard library only.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

OPENAPI_PERMISSION = re.compile(r"^\s*x-itms-permission:\s*([A-Za-z0-9.\-]+)\s*$", re.MULTILINE)
HAS_AUTHORITY = re.compile(r"hasAuthority\('([^']+)'\)")
CATALOGUE_ROW = re.compile(r"\(\s*'([a-z][a-z0-9.\-]*)'\s*,\s*'([a-z0-9\-]+)'")
PSEUDO = {"authenticated"}
CATALOGUE_DIR = Path("services/iam-service/src/main/resources/db/migration")


def openapi_permissions(service_dir: Path) -> set[str]:
    found: set[str] = set()
    for spec in (service_dir / "src/main/resources/openapi").glob("*.yaml"):
        found.update(OPENAPI_PERMISSION.findall(spec.read_text(encoding="utf-8")))
    return found - PSEUDO


def code_permissions(service_dir: Path) -> set[str]:
    found: set[str] = set()
    for src in (service_dir / "src/main/java").rglob("*.java"):
        found.update(HAS_AUTHORITY.findall(src.read_text(encoding="utf-8")))
    return found


def catalogue(repo: Path) -> dict[str, str]:
    rows: dict[str, str] = {}
    directory = repo / CATALOGUE_DIR
    for sql in sorted(directory.glob("*.sql")) if directory.exists() else []:
        text = sql.read_text(encoding="utf-8")
        for block in re.findall(r"insert into permission\b.*?;", text, flags=re.IGNORECASE | re.DOTALL):
            for code, service in CATALOGUE_ROW.findall(block):
                rows[code] = service
    return rows


def check(repo: Path) -> list[str]:
    problems: list[str] = []
    known = catalogue(repo)
    services = sorted(d for d in (repo / "services").iterdir() if (d / "src/main/resources/openapi").is_dir())
    for service_dir in services:
        declared = openapi_permissions(service_dir)
        for perm in sorted(code_permissions(service_dir) - declared):
            problems.append(f"{service_dir.name}: code checks '{perm}' but the OpenAPI does not declare it")
        for perm in sorted(declared):
            if perm not in known:
                problems.append(f"{service_dir.name}: '{perm}' is not in the iam-service permission catalogue")
            elif known[perm] != service_dir.name:
                problems.append(f"{service_dir.name}: '{perm}' is registered for '{known[perm]}' in the catalogue")
    return problems


def main() -> int:
    repo = Path(__file__).resolve().parents[2]
    problems = check(repo)
    for p in problems:
        print(f"PERMISSION CHECK: {p}")
    if problems:
        return 1
    print(f"Permission catalogue check: OK ({len(catalogue(repo))} catalogue permissions)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
