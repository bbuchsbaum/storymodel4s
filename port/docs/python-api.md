# Python API: independent accounts and explicit computation

The port preserves scientific behavior while revising its implementation. Ordinary Python
functions compose one domain model; strict JSON is an exchange boundary, not a required
intermediate step in every calculation. We consolidate working slices rather than translating
Scala's class hierarchy or accumulating parallel pipeline frameworks.

[Verification receipt](../evidence/python-api.json) records compatibility, tests and wheel checks.

Run the complete notebook-style example:

```sh
uv run python examples/python_workflow.py
```

Its `# %%` sections are usable as notebook cells. It reuses admitted synthetic text, makes
source and recall accounts independently, pairs them, and invokes the same alignment and
analysis functions used by the CLI. No provider call or file output is hidden in this API.

The principal public operations are available directly from `storymodel`:

| Operation | Responsibility |
|---|---|
| `make_account` | Construct an independent or paired v3 account; supply roots for present sides. |
| `prepare_extraction` | Bind raw recall to deterministic intake and a proposal contract. |
| `resolve_extraction` | Admit supplied proposal text; useful with independently obtained replies. |
| `import_extraction`, `replay_extraction` | Persist and replay raw replies through the existing recorded-run engine. |
| `pair_accounts` | Join independent source and recall graphs without rewriting identities. |
| `align_account` | Add missing reference bindings under an explicit policy. |
| `project` | Derive analysis views without rerunning extraction or alignment. |
| `verify_alignment` | Recompute a saved alignment receipt against the original input. |
| `read_account`, `export_account` | Validate saved accounts and enforce export grants. |

Construct primitive records from `storymodel.model`. Use the typed constructor
`Entity.create(origin_document, label, evidence)` and the `origin_document` property;
`source_document` remains the compatibility
wire name. Supplying both names refuses rather than silently choosing one. Identity
namespaces are explicit constants, so renaming a Python class does not rename its objects.
The runtime Pydantic constructor also accepts `origin_document`, but its alias is not understood
by all type checkers; `Entity.create` is the typed Python entry point. The package ships `py.typed`.

Account v3 permits exactly the narrated roots required by its document sides. A recall-only
account needs no source root; a source-only account needs no recall root. `pair_accounts`
requires independent inputs, validates their union, retains their objects and IDs, and uses
the intersection of export permissions. It does not infer cross-account entity identity,
reference bindings, fidelity or temporal correspondence. Provenance binds both input accounts.

V1 and v2 retain their previous validation and identity rules. Existing saved accounts remain
readable without migration. Legacy paired-shape requests still produce v2 extraction accounts;
independent extraction and v3 source inputs produce v3. Old v1/v2-only consumers must explicitly
add v3 support. Account schema versioning is distinct from model-response schemas and recipes.

Public operations continue to validate copied or externally constructed values. Within a
validated operation, hashing reuses that checked snapshot instead of serializing and validating
the whole account again. The strict JSON parser is shared across profiles, including duplicate
field and nonfinite-number refusal. Historical `model.canonical`, `model.digest`, and
`study_workflow.strict_json` imports remain compatibility exports.

The specialized film/time profile remains supported. It is not forced into an event/detail
account, and the older complete-account replay helper remains a compatibility entry point.
All recorded paths already share `RecordedRun`; adding another generic runner would increase
indirection without removing meaningful work. No separate dataclass/Pydantic domain hierarchy
or new dependency is introduced.

This consolidation improves the API and removes specific redundant work. It does not establish
a general performance advantage or computational parity with the complete Scala model.
