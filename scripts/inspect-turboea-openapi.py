#!/usr/bin/env python3
"""Summarize a saved Turbo EA OpenAPI document without network calls or writes.

This inventories the integration surface; it does not verify deployed behavior,
permissions, uniqueness, PATCH semantics, or delivery guarantees. Exit 1 means
an operation/schema used by the M0 design is missing; exit 2 means invalid input.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys


OPERATIONS = {
    "/api/health": ("get",),
    "/api/v1/auth/login": ("post",),
    "/api/v1/auth/refresh": ("post",),
    "/api/v1/cards": ("get", "post"),
    "/api/v1/cards/{card_id}": ("get", "patch"),
    "/api/v1/relations": ("get", "post"),
    "/api/v1/relations/{rel_id}": ("patch",),
    "/api/v1/metamodel/types": ("get",),
    "/api/v1/metamodel/relation-types": ("get",),
    "/api/v1/events/stream": ("get",),
}
SCHEMAS = (
    "LoginRequest", "TokenResponse", "CardCreate", "CardUpdate",
    "CardResponse", "CardListResponse", "RelationCreate", "RelationUpdate",
    "RelationResponse",
)


def content_schemas(content):
    return {media: value.get("schema", {}) for media, value in content.items()}


def inspect_document(raw):
    document = json.loads(raw)
    if not isinstance(document, dict) or not isinstance(document.get("paths"), dict):
        raise ValueError("Expected an OpenAPI object with a paths object")
    if not str(document.get("openapi", "")).startswith("3."):
        raise ValueError("Expected OpenAPI 3.x")
    components = document.get("components", {})
    missing, operations, schemas = [], {}, {}
    for path, methods in OPERATIONS.items():
        path_item = document["paths"].get(path, {})
        for method in methods:
            key = f"{method.upper()} {path}"
            if method not in path_item:
                missing.append(key)
                continue
            operation = path_item[method]
            parameters = path_item.get("parameters", []) + operation.get("parameters", [])
            operations[key] = {
                "parameters": [
                    {k: v for k, v in p.items() if k in ("$ref", "name", "in", "required", "schema")}
                    for p in parameters
                ],
                "request": content_schemas(operation.get("requestBody", {}).get("content", {})),
                "responses": {
                    status: content_schemas(response.get("content", {}))
                    for status, response in operation.get("responses", {}).items()
                },
                # null means undeclared, NOT unauthenticated.
                "declared_security": operation.get("security", document.get("security")),
            }
    for name in SCHEMAS:
        schema = components.get("schemas", {}).get(name)
        if schema is None:
            missing.append(f"schema {name}")
        else:
            schemas[name] = {
                "required": schema.get("required", []),
                "properties": sorted(schema.get("properties", {})),
            }
    return {
        "scope": "Static M0 inventory; deployment and behavioral verification required",
        "sha256": hashlib.sha256(raw).hexdigest(),
        "openapi": document["openapi"],
        "info": document.get("info", {}),
        "path_count": len(document["paths"]),
        "security_schemes": components.get("securitySchemes", {}),
        "operations": operations,
        "schemas": schemas,
        "missing": missing,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("openapi", type=Path, help="saved OpenAPI JSON (no URL)")
    args = parser.parse_args()
    try:
        report = inspect_document(args.openapi.read_bytes())
    except (OSError, ValueError, TypeError, AttributeError) as error:
        parser.exit(2, f"Cannot inspect OpenAPI: {error}\n")
    print(json.dumps(report, indent=2, sort_keys=True))
    return 1 if report["missing"] else 0


if __name__ == "__main__":
    sys.exit(main())
