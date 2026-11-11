"""Offline inventory failures must be visible, without claiming runtime support."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/inspect-turboea-openapi.py"
SPEC = importlib.util.spec_from_file_location("inspect_turboea", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class OpenApiInventoryTest(unittest.TestCase):
    def test_missing_surface_is_reported_and_cli_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "openapi.json"
            source.write_text(json.dumps({"openapi": "3.1.0", "paths": {}}))
            result = subprocess.run([sys.executable, str(SCRIPT), str(source)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("GET /api/v1/cards", json.loads(result.stdout)["missing"])

    def test_auth_omission_is_distinct_from_explicit_anonymous_operation(self):
        document = {"openapi": "3.1.0", "paths": {
            "/api/v1/cards": {"get": {}, "post": {"security": []}},
        }}
        report = AUDIT.inspect_document(json.dumps(document).encode())
        self.assertIsNone(report["operations"]["GET /api/v1/cards"]["declared_security"])
        self.assertEqual([], report["operations"]["POST /api/v1/cards"]["declared_security"])
        document["security"] = [{"bearer": []}]
        report = AUDIT.inspect_document(json.dumps(document).encode())
        self.assertEqual([{"bearer": []}], report["operations"]["GET /api/v1/cards"]["declared_security"])

    def test_response_shape_and_inherited_parameters_are_retained(self):
        document = {"openapi": "3.1.0", "paths": {"/api/v1/cards": {
            "parameters": [{"in": "header", "name": "If-Match", "schema": {"type": "string"}}],
            "get": {"responses": {"200": {"content": {"application/json": {
                "schema": {"type": "array", "items": {"type": "string"}},
            }}}}},
        }}}
        operation = AUDIT.inspect_document(json.dumps(document).encode())["operations"]["GET /api/v1/cards"]
        self.assertEqual("If-Match", operation["parameters"][0]["name"])
        self.assertEqual("array", operation["responses"]["200"]["application/json"]["type"])

    def test_invalid_input_fails_cleanly(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "invalid.json"
            source.write_text("[]")
            result = subprocess.run([sys.executable, str(SCRIPT), str(source)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("Cannot inspect OpenAPI", result.stderr)
        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
