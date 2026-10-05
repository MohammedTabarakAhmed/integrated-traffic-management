"""Tests for lint_openapi.py (API_SPECIFICATION §1-§3, INT-003, SEC-022, ADR-032, ADR-036)."""
from __future__ import annotations

import copy
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lint_openapi  # noqa: E402

GOOD = """
openapi: 3.1.0
info: {title: Test, version: 1.0.0}
security: [{bearerAuth: []}]
paths:
  /v1/holds/{id}/lifts:
    post:
      operationId: liftHold
      summary: Lift a hold
      tags: [Holds]
      x-itms-permission: hold.lift
      x-itms-scope: [module]
      parameters:
        - {name: id, in: path, required: true, schema: {type: string, format: uuid}}
        - {$ref: '#/components/parameters/IdempotencyKey'}
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/Lift'}
      responses:
        '200': {description: ok}
        '401': {$ref: '#/components/responses/Problem'}
        '403': {$ref: '#/components/responses/Problem'}
  /v1/me/holds:
    get:
      operationId: myHolds
      summary: My holds
      tags: [Holds]
      x-itms-permission: authenticated
      x-itms-scope: [own]
      responses:
        '200': {description: ok}
        '401': {$ref: '#/components/responses/Problem'}
components:
  parameters:
    IdempotencyKey: {name: Idempotency-Key, in: header, required: true, schema: {type: string, maxLength: 128}}
  responses:
    Problem:
      description: problem
      content:
        application/problem+json:
          schema: {type: object}
  schemas:
    Lift:
      type: object
      additionalProperties: false
      properties:
        reason: {type: string, maxLength: 500}
        codes:
          type: object
          propertyNames: {pattern: '^[A-Z]+$'}
          additionalProperties: {type: string, maxLength: 10}
"""
IDEMPOTENT = ["/v1/holds/*/lifts"]


def rules(spec: dict, idempotent=IDEMPOTENT) -> list[str]:
    return [p.split(" ")[1] for p in lint_openapi.lint(spec, "t.yaml", idempotent)]


class LintTest(unittest.TestCase):

    def setUp(self) -> None:
        self.spec = lint_openapi.load(GOOD)
        self.lift = self.spec["paths"]["/v1/holds/{id}/lifts"]["post"]

    def test_good_spec_passes(self) -> None:
        self.assertEqual([], lint_openapi.lint(self.spec, "t.yaml", IDEMPOTENT))

    def test_repeated_key_is_refused(self) -> None:
        with self.assertRaises(lint_openapi.DuplicateKey):
            lint_openapi.load("a: 1\nb: 2\na: 3\n")

    def test_version_and_info(self) -> None:
        self.spec["openapi"] = "3.0.3"
        del self.spec["info"]["version"]
        self.assertEqual(["L01", "L01"], rules(self.spec))

    def test_paths_and_methods(self) -> None:
        paths = self.spec["paths"]
        paths["/v2/Holds"] = paths.pop("/v1/me/holds")
        paths["/v1/holds/{id}/lifts"]["patch"] = copy.deepcopy(self.lift)
        self.assertEqual(["L02", "L02", "L03"], sorted(rules(self.spec)))

    def test_operation_metadata(self) -> None:
        self.spec["paths"]["/v1/me/holds"]["get"]["operationId"] = "liftHold"
        del self.lift["summary"]
        self.lift["tags"] = []
        self.assertEqual(["L04", "L04", "L04"], sorted(rules(self.spec)))

    def test_permission_and_scope(self) -> None:
        del self.lift["x-itms-permission"]
        for bad in (None, "region", [], ["module", "module"], ["planet"]):
            self.lift["x-itms-scope"] = bad
            self.assertEqual(["L05", "L06"], sorted(rules(self.spec)), bad)
        self.lift["x-itms-scope"] = "national"
        self.assertEqual(["L05"], rules(self.spec))

    def test_401_and_403(self) -> None:
        del self.lift["responses"]["403"]
        del self.spec["paths"]["/v1/me/holds"]["get"]["responses"]["401"]
        self.assertEqual(["L07", "L07"], rules(self.spec))
        self.spec["paths"]["/v1/me/holds"]["get"]["security"] = []
        self.assertEqual(["L07"], rules(self.spec), "an open operation needs no 401")

    def test_errors_are_problem_json(self) -> None:
        self.lift["responses"]["409"] = {"description": "x", "content": {"application/json": {"schema": {}}}}
        self.assertEqual(["L08"], rules(self.spec))

    def test_request_bodies(self) -> None:
        lift = self.spec["components"]["schemas"]["Lift"]
        del lift["additionalProperties"]
        lift["properties"]["note"] = {"type": "string"}
        del lift["properties"]["codes"]["propertyNames"]
        self.lift["requestBody"]["content"]["text/plain"] = {"schema": {"type": "string", "maxLength": 3}}
        self.assertEqual(["L09", "L09", "L09", "L09"], rules(self.spec))

    def test_unresolved_ref_and_path_parameter(self) -> None:
        self.lift["parameters"] = [{"$ref": "#/components/parameters/Nope"}]
        self.assertEqual(["L10", "L11", "L12"], sorted(rules(self.spec)))

    def test_idempotency_key_matches_configuration(self) -> None:
        self.assertEqual(["L12"], rules(self.spec, idempotent=[]))
        self.lift["parameters"].pop()
        self.assertEqual(["L12"], rules(self.spec))
        self.assertEqual([], rules(self.spec, idempotent=[]))

    def test_repository_specifications_pass(self) -> None:
        count, problems = lint_openapi.check(Path(__file__).resolve().parents[2])
        self.assertGreaterEqual(count, 4)
        self.assertEqual([], problems)

    def test_service_configuration_is_read(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            service = Path(tmp) / "services" / "x"
            (service / "src/main/resources/openapi").mkdir(parents=True)
            (service / "src/main/resources/application.yml").write_text(
                "itms:\n  idempotency:\n    enabled: true\n    paths: [/v1/holds/*/lifts]\n", encoding="utf-8")
            (service / "src/main/resources/openapi/x.v1.yaml").write_text(GOOD + "openapi: 3.1.0\n",
                                                                         encoding="utf-8")
            count, problems = lint_openapi.check(Path(tmp))
            self.assertEqual(1, count)
            self.assertEqual(1, len(problems))
            self.assertIn("L00", problems[0])


if __name__ == "__main__":
    unittest.main()
