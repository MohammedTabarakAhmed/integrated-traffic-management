#!/usr/bin/env python3
"""OpenAPI lint for the ITMS API conventions (API_SPECIFICATION §1-§3, INT-003, SEC-022, ADR-032, ADR-036).

Every `services/<service>/src/main/resources/openapi/*.yaml` must follow the conventions the services are built on:
  L00 valid YAML without repeated keys (a repeated key silently replaces the first one);
  L01 OpenAPI 3.1 with info.title and info.version;
  L02 paths start with /v1/ and use plural kebab-case segments or {camelCase} parameters;
  L03 only GET, POST, PUT and DELETE (INT-003);
  L04 each operation has a unique operationId, a summary and at least one tag;
  L05 each operation declares x-itms-permission (CC-001, ADR-022);
  L06 each operation declares x-itms-scope: `national`, or a list of distinct dimensions from own, region, centre,
      module (ADR-036);
  L07 a secured operation documents 401, and 403 unless its permission is `authenticated` (any signed-in subject);
      permission `public` is only for operations with `security: []` (deliberately public pages, CC-083);
  L08 error responses (4xx/5xx) with a body use application/problem+json only (CH-053, §3);
  L09 request bodies are application/json (or, only on an operation marked `x-itms-binary-upload: true`, a single
      application/octet-stream body of type string/binary: uploads to document-service, ADR-051); request objects are closed (`additionalProperties: false`, or a map
      whose keys have a pattern and whose values have a schema) and request strings are bounded (maxLength, pattern,
      enum, format or const) (SEC-022);
  L10 every internal $ref resolves;
  L11 every {parameter} of a path is declared as a required path parameter;
  L12 an Idempotency-Key header (required) is declared exactly on the POSTs the service enforces it for
      (`itms.idempotency.paths` in its application.yml, ADR-032).

Exit code 0 = OK, 1 = violations found. Needs PyYAML (tools/ci/requirements.txt).
"""
from __future__ import annotations

import fnmatch
import re
import sys
from pathlib import Path

import yaml

METHODS = {"get", "post", "put", "delete"}
NOT_ALLOWED = {"patch", "head", "options", "trace"}
SCOPE_DIMENSIONS = {"own", "region", "centre", "module"}
SEGMENT = re.compile(r"^(?:[a-z][a-z0-9]*(?:-[a-z0-9]+)*|\{[a-z][A-Za-z0-9]*\})$")
BOUNDED_STRING_KEYS = ("maxLength", "pattern", "enum", "format", "const")


class DuplicateKey(Exception):
    pass


class StrictLoader(yaml.SafeLoader):
    """SafeLoader that refuses a key repeated in the same mapping (L00)."""


def _strict_mapping(loader: StrictLoader, node: yaml.MappingNode, deep: bool = False) -> dict:
    keys = set()
    for key_node, _ in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in keys:
            raise DuplicateKey(f"line {key_node.start_mark.line + 1}: key {key!r} is repeated")
        keys.add(key)
    return loader.construct_mapping(node, deep=deep)


StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _strict_mapping)


def load(text: str) -> dict:
    return yaml.load(text, Loader=StrictLoader)


def resolve(spec: dict, ref: str):
    """The node an internal $ref points to, or None."""
    if not ref.startswith("#/"):
        return None
    node = spec
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def deref(spec: dict, node):
    seen = set()
    while isinstance(node, dict) and "$ref" in node and node["$ref"] not in seen:
        seen.add(node["$ref"])
        node = resolve(spec, node["$ref"])
    return node


def all_refs(node, out: list[str]) -> list[str]:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                out.append(value)
            else:
                all_refs(value, out)
    elif isinstance(node, list):
        for value in node:
            all_refs(value, out)
    return out


def request_schema_problems(spec: dict, schema, where: str, seen: tuple = ()) -> list[str]:
    """L09: open objects and unbounded strings anywhere in a request schema."""
    problems: list[str] = []
    if not isinstance(schema, dict):
        return problems
    if "$ref" in schema:
        ref = schema["$ref"]
        if ref in seen:
            return problems
        name = ref.rsplit("/", 1)[-1]
        return request_schema_problems(spec, resolve(spec, ref), f"{where}>{name}", seen + (ref,))
    for key in ("oneOf", "anyOf", "allOf"):
        for option in schema.get(key, []):
            problems += request_schema_problems(spec, option, where, seen)
    types = schema.get("type")
    types = set(types) if isinstance(types, list) else {types}
    if "object" in types or "properties" in schema:
        extra = schema.get("additionalProperties")
        bounded_map = (isinstance(extra, dict) and isinstance(schema.get("propertyNames"), dict)
                       and "pattern" in schema["propertyNames"])
        if extra is not False and not bounded_map:
            problems.append(f"L09 {where}: request object must set additionalProperties: false (SEC-022)")
        if isinstance(extra, dict):
            problems += request_schema_problems(spec, extra, f"{where}.*", seen)
        for name, prop in (schema.get("properties") or {}).items():
            problems += request_schema_problems(spec, prop, f"{where}.{name}", seen)
    if "array" in types:
        problems += request_schema_problems(spec, schema.get("items"), f"{where}[]", seen)
    if "string" in types and not any(key in schema for key in BOUNDED_STRING_KEYS):
        problems.append(f"L09 {where}: request string needs maxLength, pattern, enum, format or const (SEC-022)")
    return problems


def idempotent_paths(service_dir: Path) -> list[str]:
    config = service_dir / "src/main/resources/application.yml"
    if not config.exists():
        return []
    data = yaml.safe_load(config.read_text(encoding="utf-8")) or {}
    idempotency = (data.get("itms") or {}).get("idempotency") or {}
    if not idempotency.get("enabled", False):
        return []
    return list(idempotency.get("paths") or [])


def matches(path: str, patterns: list[str]) -> bool:
    """Spring-style patterns of `itms.idempotency.paths`: `*` stands for one path segment."""
    concrete = re.sub(r"\{[^/]+\}", "x", path)
    return any(fnmatch.fnmatchcase(concrete, p) and concrete.count("/") == p.count("/") for p in patterns)


def lint(spec: dict, name: str, idempotent: list[str]) -> list[str]:
    problems: list[str] = []

    def add(rule: str, text: str) -> None:
        problems.append(f"{name}: {rule} {text}")

    if not str(spec.get("openapi", "")).startswith("3.1"):
        add("L01", f"openapi must be 3.1.x, not {spec.get('openapi')!r}")
    info = spec.get("info") or {}
    if not info.get("title") or not info.get("version"):
        add("L01", "info.title and info.version are required")

    for ref in sorted(set(all_refs(spec, []))):
        if ref.startswith("#/") and resolve(spec, ref) is None:
            add("L10", f"unresolved $ref {ref}")

    global_security = spec.get("security")
    operation_ids: dict[str, str] = {}
    for path, item in (spec.get("paths") or {}).items():
        if not path.startswith("/v1/"):
            add("L02", f"{path}: paths start with /v1/")
        for segment in path.strip("/").split("/")[1:]:
            if not SEGMENT.match(segment):
                add("L02", f"{path}: segment {segment!r} is not kebab-case or a {{camelCase}} parameter")
        path_params = set(re.findall(r"\{([^}]+)\}", path))
        shared = [deref(spec, p) for p in item.get("parameters", [])]
        for method, op in item.items():
            if method in ("parameters", "summary", "description", "servers"):
                continue
            where = f"{method.upper()} {path}"
            if method in NOT_ALLOWED or method not in METHODS:
                add("L03", f"{where}: only GET, POST, PUT and DELETE are allowed (INT-003)")
                continue
            op_id = op.get("operationId")
            if not op_id:
                add("L04", f"{where}: operationId is required")
            elif op_id in operation_ids:
                add("L04", f"{where}: operationId {op_id} is also used by {operation_ids[op_id]}")
            else:
                operation_ids[op_id] = where
            if not op.get("summary"):
                add("L04", f"{where}: summary is required")
            if not op.get("tags"):
                add("L04", f"{where}: at least one tag is required")

            permission = op.get("x-itms-permission")
            if not permission:
                add("L05", f"{where}: x-itms-permission is required")
            scope = op.get("x-itms-scope")
            if scope != "national" and not (isinstance(scope, list) and scope and len(set(scope)) == len(scope)
                                            and set(scope) <= SCOPE_DIMENSIONS):
                add("L06", f"{where}: x-itms-scope must be national or a list of distinct values of "
                           f"{sorted(SCOPE_DIMENSIONS)}, not {scope!r}")

            responses = op.get("responses") or {}
            secured = op.get("security", global_security) not in (None, [])
            if secured and "401" not in responses:
                add("L07", f"{where}: a secured operation documents 401")
            if permission == "public" and secured:
                add("L07", f"{where}: a public operation sets security: [] (no token)")
            if secured and permission != "authenticated" and "403" not in responses:
                add("L07", f"{where}: an operation behind a permission documents 403")
            for status, response in responses.items():
                if str(status)[0] in "45":
                    content = (deref(spec, response) or {}).get("content") or {}
                    wrong = [ct for ct in content if ct != "application/problem+json"]
                    if wrong:
                        add("L08", f"{where} {status}: errors use application/problem+json, not {wrong}")

            body = deref(spec, op.get("requestBody"))
            if body:
                content = body.get("content") or {}
                binary = op.get("x-itms-binary-upload") is True
                if binary:
                    schema = deref(spec, (content.get("application/octet-stream") or {}).get("schema")) or {}
                    if set(content) != {"application/octet-stream"} or schema.get("type") != "string"                             or schema.get("format") != "binary":
                        add("L09", f"{where}: a binary upload has one application/octet-stream body of type "
                                   f"string, format binary")
                    content = {}
                elif set(content) != {"application/json"}:
                    add("L09", f"{where}: request bodies are application/json only, not {sorted(content)}")
                for media in content.values():
                    for problem in request_schema_problems(spec, media.get("schema"), op_id or where):
                        add(problem[:3], problem[4:])

            params = shared + [deref(spec, p) for p in op.get("parameters", [])]
            declared = {p.get("name") for p in params if isinstance(p, dict) and p.get("in") == "path"
                        and p.get("required") is True}
            for missing in sorted(path_params - declared):
                add("L11", f"{where}: path parameter {{{missing}}} is not declared as a required path parameter")

            keys = [p for p in params if isinstance(p, dict) and p.get("in") == "header"
                    and str(p.get("name", "")).lower() == "idempotency-key"]
            enforced = method == "post" and matches(path, idempotent)
            if enforced and not any(p.get("required") is True for p in keys):
                add("L12", f"{where}: the service requires an Idempotency-Key here; declare it as required")
            if keys and not enforced:
                add("L12", f"{where}: declares Idempotency-Key but the service does not enforce it "
                           "(itms.idempotency.paths)")
    return problems


def check(repo: Path) -> tuple[int, list[str]]:
    problems: list[str] = []
    count = 0
    for service_dir in sorted((repo / "services").glob("*/")):
        idempotent = idempotent_paths(service_dir)
        for spec_file in sorted((service_dir / "src/main/resources/openapi").glob("*.yaml")):
            count += 1
            try:
                spec = load(spec_file.read_text(encoding="utf-8"))
            except (DuplicateKey, yaml.YAMLError) as e:
                problems.append(f"{spec_file.name}: L00 {e}")
                continue
            problems += lint(spec, spec_file.name, idempotent)
    return count, problems


def main() -> int:
    repo = Path(__file__).resolve().parents[2]
    count, problems = check(repo)
    for problem in problems:
        print(problem)
    if problems:
        print(f"OpenAPI lint: {len(problems)} violation(s) in {count} specification(s)")
        return 1
    print(f"OpenAPI lint: OK ({count} specifications)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
