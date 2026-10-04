"""Tests for check_permissions.py.  @req CC-005, CC-001"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import check_permissions as cp


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class CheckPermissionsTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        write(self.repo / cp.CATALOGUE_DIR / "V2__seed.sql", """
            insert into permission (code, service, description_nl, description_en) values
                ('demo.read', 'demo-service', 'ع', 'Read'),
                ('demo.write', 'demo-service', 'ع', 'Write');
            insert into role (code) values ('NOT_A_PERMISSION', 'x');
        """)
        write(self.repo / "services/demo-service/src/main/resources/openapi/demo.v1.yaml", """
            paths:
              /v1/a:
                get:
                  x-itms-permission: demo.read
              /v1/me:
                get:
                  x-itms-permission: authenticated
        """)
        write(self.repo / "services/demo-service/src/main/java/Demo.java",
              "@PreAuthorize(\"hasAuthority('demo.read')\") class Demo {}")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_consistent_repository_passes(self) -> None:
        self.assertEqual([], cp.check(self.repo))
        self.assertEqual({"demo.read": "demo-service", "demo.write": "demo-service"}, cp.catalogue(self.repo))

    def test_code_permission_missing_from_openapi_fails(self) -> None:
        write(self.repo / "services/demo-service/src/main/java/More.java",
              "@PreAuthorize(\"hasAuthority('demo.write')\") class More {}")
        self.assertEqual(["demo-service: code checks 'demo.write' but the OpenAPI does not declare it"],
                         cp.check(self.repo))

    def test_openapi_permission_missing_from_catalogue_fails(self) -> None:
        write(self.repo / "services/demo-service/src/main/resources/openapi/extra.v1.yaml",
              "      x-itms-permission: demo.delete\n")
        self.assertEqual(["demo-service: 'demo.delete' is not in the iam-service permission catalogue"],
                         cp.check(self.repo))

    def test_permission_registered_for_another_service_fails(self) -> None:
        write(self.repo / "services/other-service/src/main/resources/openapi/o.v1.yaml",
              "      x-itms-permission: demo.read\n")
        self.assertEqual(["other-service: 'demo.read' is registered for 'demo-service' in the catalogue"],
                         cp.check(self.repo))


if __name__ == "__main__":
    unittest.main()
