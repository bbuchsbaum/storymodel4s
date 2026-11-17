"""Historical wire schemas are adapters, not a second set of graph algorithms."""

import json
from importlib.resources import files
from typing import Any, Literal

from pydantic.json_schema import GenerateJsonSchema, JsonSchemaMode


def preserve_legacy_account_schema(schema: dict[str, Any], ref_template: str) -> dict[str, Any]:
    """Restore embedded legacy-only graphs; mixed rich envelopes retain the current union.

    Pydantic builds nested models from core schemas without calling their public
    model_json_schema override. Apply the same adapter to the completed outer schema,
    then remove definitions made unreachable by the historical graph replacement.
    """
    definitions = schema.get("$defs", {})
    if "Account" not in definitions or "RichAccount" in definitions:
        return schema
    historical = legacy_account_schema(ref_template=ref_template)
    definitions.update(historical.pop("$defs", {}))
    definitions["Account"] = historical
    by_ref = {ref_template.format(model=name): name for name in definitions}
    visited: set[str] = set()

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            ref = value.get("$ref")
            name = by_ref.get(ref) if isinstance(ref, str) else None
            if name is not None and name not in visited:
                visited.add(name)
                visit(definitions[name])
            for key, child in value.items():
                if key != "$defs":
                    visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(schema)
    schema["$defs"] = {name: value for name, value in definitions.items() if name in visited}
    return schema


def legacy_account_schema(
    by_alias: bool | None = None,
    ref_template: str = "#/$defs/{model}",
    schema_generator: type[GenerateJsonSchema] = GenerateJsonSchema,
    mode: JsonSchemaMode = "validation",
    *,
    union_format: Literal["any_of", "primitive_type_array"] = "any_of",
) -> dict[str, Any]:
    """Return the unchanged v1-v3 wire contract, enforced by Account's contextual validator."""
    if schema_generator is not GenerateJsonSchema:
        raise ValueError("custom schema generators cannot rewrite a frozen legacy wire contract")
    schema: dict[str, Any] = json.loads(
        files("storymodel").joinpath("_legacy_account_schema.json").read_text(encoding="utf-8")
    )

    # The historical record has no alias/mode-dependent emitted field names. Alternative
    # reference templates remain supported; changing JSON layout cannot widen vocabulary.
    def references(value: Any) -> None:
        if isinstance(value, dict):
            ref = value.get("$ref")
            if isinstance(ref, str) and ref.startswith("#/$defs/"):
                value["$ref"] = ref_template.format(model=ref.removeprefix("#/$defs/"))
            for child in value.values():
                references(child)
        elif isinstance(value, list):
            for child in value:
                references(child)

    references(schema)
    return schema


def legacy_rich_schema(version: Literal["v4", "v5"]) -> dict[str, Any]:
    """Expose a historical rich wire contract without media fields or media support variants."""
    schema: dict[str, Any] = json.loads(
        files("storymodel").joinpath("_legacy_rich_schema.json").read_text(encoding="utf-8")
    )
    schema["properties"]["schema_version"] = {
        "const": f"storymodel.account/{version}",
        "default": f"storymodel.account/{version}",
        "title": "Schema Version",
        "type": "string",
    }
    if version == "v4":
        schema["properties"]["contexts"]["items"] = {"$ref": "#/$defs/Context"}
        schema["$defs"].pop("ExtendedContext", None)
    return schema


def legacy_rich_v6_schema() -> dict[str, Any]:
    """Return the frozen v6 rich-account schema before v7 vocabulary was introduced."""
    schema: dict[str, Any] = json.loads(
        files("storymodel").joinpath("_legacy_rich_v6_schema.json").read_text(encoding="utf-8")
    )
    return schema


def legacy_rich_v7_schema() -> dict[str, Any]:
    """Return the frozen v7 rich-account schema before v8 assessment evidence was added."""
    schema: dict[str, Any] = json.loads(
        files("storymodel").joinpath("_legacy_rich_v7_schema.json").read_text(encoding="utf-8")
    )
    return schema
