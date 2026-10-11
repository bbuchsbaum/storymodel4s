#!/usr/bin/env python3
"""Dependency-free wire courts for caption request schema binding."""

import ast
import json
from pathlib import Path
import unittest


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "caption_worker.py"
RECORDED_V1 = HERE.parent / "src/test/resources/f0/f0-v1.caption-request.json"


def validator():
    tree = ast.parse(SOURCE.read_text(encoding="utf-8"), filename=str(SOURCE))
    function = next(
        node
        for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "validate_request_binding"
    )
    module = ast.Module(body=[function], type_ignores=[])
    namespace = {"REQUEST_SCHEMA": "storymodel4s.media.caption-request"}
    exec(compile(module, str(SOURCE), "exec"), namespace)
    return namespace["validate_request_binding"]


class CaptionRequestSchemaTest(unittest.TestCase):
    def setUp(self):
        self.validate = validator()
        self.v1 = json.loads(RECORDED_V1.read_text(encoding="utf-8"))
        self.v2 = json.loads(json.dumps(self.v1))
        self.v2["schemaVersion"] = 2
        self.v2["frames"]["identity"] = "a" * 64

    def test_recorded_v1_remains_accepted(self):
        self.assertIsNone(self.validate(self.v1))

    def test_v2_with_a_selection_identity_is_accepted(self):
        self.assertIsNone(self.validate(self.v2))

    def test_v2_without_a_selection_identity_refuses(self):
        del self.v2["frames"]["identity"]
        self.assertEqual(
            self.validate(self.v2), "v2 request names no frame-selection identity"
        )

    def test_v2_with_a_bad_selection_identity_refuses(self):
        self.v2["frames"]["identity"] = "not-a-checksum"
        self.assertEqual(
            self.validate(self.v2), "v2 request names no frame-selection identity"
        )


if __name__ == "__main__":
    unittest.main()
