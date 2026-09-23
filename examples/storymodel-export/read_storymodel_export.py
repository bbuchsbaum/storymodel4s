#!/usr/bin/env python3
"""Independent reader for storymodel-export/v0.1 (ADR 0020). Standard library only.

It re-derives every check from the documented wire and never imports producer logic:
file set, SHA-256 and byte length, column schema, canonical quoted-TSV bytes, cell types,
canonical JSON cells, joins between tables, span offsets, and the loss-record schema and internal
accounting. It cannot establish that a dropped-item count is true: that needs the bound
canonical model, which the bundle deliberately does not carry.

Usage: read_storymodel_export.py DIRECTORY
Prints a JSON summary on success; exits 1 with the first refusal otherwise.
"""
import csv
import hashlib
import io
import json
import os
import re
import sys

SCHEMA = "storymodel-export/v0.1"
TABLES = [
    "nodes.tsv",
    "entities.tsv",
    "situations.tsv",
    "contexts.tsv",
    "segments.tsv",
    "relations.tsv",
    "circumstances.tsv",
    "hierarchy.tsv",
    "evidence.tsv",
    "spans.tsv",
]
MANIFEST_KEYS = {
    "schemaVersion",
    "model_digest",
    "model_status",
    "storymodel_schema_version",
    "story_id",
    "source_checksum",
    "capabilities",
    "exported_claims",
    "model_claims",
    "losses",
    "files",
}
CAPABILITIES = {
    "text_evidence": {"status": "present"},
    "playback_evidence": {"status": "unavailable", "reason": "text-model-only/v0.1"},
    "canonical_model": {"status": "unavailable", "reason": "not-embedded/v0.1"},
}
LOSSES = {
    "trajectory-steps",
    "boundary-beliefs",
    "feature-spaces",
    "sidecars",
    "feature-refs",
    "descriptors",
    "hypotheses",
    "sensory-profiles",
    "scoped-attributes",
    "mentions",
    "resolved-alternatives",
    "resolved-value-claims",
    "claim-credence-provenance",
    "evidence-extractors",
    "build-receipt",
    "source-text",
    "surface-units",
}
INTEGER = re.compile(r"0|-?[1-9][0-9]*")
BITS = re.compile(r"0x[0-9a-f]{16}\Z")
HEX64 = re.compile(r"[0-9a-f]{64}\Z")


T, I, B, J = "utf8-string", "decimal-integer", "ieee754-binary64-hex", "canonical-json"
# The v0.1 column set is part of the contract; a reader must not accept a renamed, retyped,
# added or dropped column merely because the manifest describes it consistently.
SCHEMAS = {
    "nodes.tsv": [("node_id", T), ("node_kind", T), ("claim_id", T), ("claim_status", T), ("context", J)],
    "entities.tsv": [("node_id", T), ("entity_type", J), ("label", T)],
    "situations.tsv": [
        ("node_id", T), ("situation_kind", T), ("predicate_lemma", T), ("frame", J), ("gloss", T),
        ("description", T), ("polarity", T), ("modality", T), ("aspect", J),
        ("discourse_position", J),
    ],
    "contexts.tsv": [("node_id", T), ("context_kind", T), ("holder", J)],
    "segments.tsv": [("node_id", T), ("segment_kind", T), ("level", I), ("summary", J)],
    "relations.tsv": [
        ("claim_id", T), ("layer", T), ("from", T), ("relation", J), ("to", T), ("context", J),
        ("claim_status", T),
    ],
    "circumstances.tsv": [
        ("claim_id", T), ("situation", T), ("circumstance_kind", T), ("label", T),
        ("claim_status", T),
    ],
    "hierarchy.tsv": [
        ("claim_id", T), ("member_kind", T), ("member", T), ("parent", T), ("hierarchy_kind", T),
        ("weight", B), ("claim_status", T),
    ],
    "evidence.tsv": [
        ("claim_id", T), ("evidence_index", I), ("evidence_id", T), ("stage", T), ("upstream", J),
        ("span_count", I),
    ],
    "spans.tsv": [
        ("claim_id", T), ("origin", T), ("evidence_index", J), ("ref_index", I),
        ("surface_unit", J), ("utf16_start", I), ("utf16_end_exclusive", I),
    ],
}
STATUSES = {
    "SurfaceExplicit", "LinguisticallyEntailed", "WorldKnowledgeInferred", "StructurallyDerived",
    "Hypothesized", "HumanAdjudicated",
}


class Refusal(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Refusal(message)


def strict_json(text):
    def pairs(items):
        keys = [k for k, _ in items]
        require(len(keys) == len(set(keys)), f"duplicate JSON key in {text[:60]!r}")
        return dict(items)

    def constant(name):
        raise Refusal(f"non-finite JSON number {name}")

    try:
        return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)
    except json.JSONDecodeError as e:
        raise Refusal(f"invalid JSON {text[:60]!r}: {e}") from None


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def count(value, what):
    require(
        isinstance(value, str) and INTEGER.fullmatch(value), f"{what}: not a decimal string"
    )
    n = int(value)
    require(n >= 0, f"{what}: negative")
    return n


def read_table(name, text, descriptor, digest):
    columns = descriptor["columns"]
    names = [c["name"] for c in columns]
    pinned = [("model_digest", T)] + SCHEMAS[name]
    require(
        [(c.get("name"), c.get("type")) for c in columns] == pinned,
        f"{name}: columns differ from the v0.1 schema",
    )
    require(names[0] == "model_digest", f"{name}: first column is not model_digest")
    rows = list(
        csv.reader(
            io.StringIO(text, newline=""), delimiter="\t", quotechar='"', strict=True
        )
    )
    buffer = io.StringIO(newline="")
    csv.writer(
        buffer, delimiter="\t", quoting=csv.QUOTE_ALL, lineterminator="\n"
    ).writerows(rows)
    require(buffer.getvalue() == text, f"{name}: not canonical quoted-TSV")
    require(
        rows and rows[0] == names, f"{name}: header differs from the manifest schema"
    )
    body = rows[1:]
    require(
        len(body) == count(descriptor["rows"], f"{name} rows"), f"{name}: row count"
    )
    out = []
    for i, row in enumerate(body):
        require(len(row) == len(names), f"{name}: ragged row {i}")
        record = {}
        for column, cell in zip(columns, row):
            kind = column["type"]
            if kind == "utf8-string":
                value = cell
            elif kind == "decimal-integer":
                require(
                    INTEGER.fullmatch(cell),
                    f"{name}.{column['name']}: bad integer {cell!r}",
                )
                value = int(cell)
            elif kind == "ieee754-binary64-hex":
                require(
                    BITS.fullmatch(cell), f"{name}.{column['name']}: bad binary64 {cell!r}"
                )
                exponent = (int(cell[2:], 16) >> 52) & 0x7FF
                require(exponent != 0x7FF, f"{name}.{column['name']}: non-finite")
                value = cell
            elif kind == "canonical-json":
                value = strict_json(cell)
                require(
                    canonical_json(value) == cell,
                    f"{name}.{column['name']}: not canonical JSON",
                )
                require(
                    isinstance(value, dict) and "status" in value,
                    f"{name}.{column['name']}: structured cell without a status",
                )
            else:
                raise Refusal(f"{name}: unknown column type {kind}")
            record[column["name"]] = value
        require(
            record["model_digest"] == digest, f"{name}: foreign model_digest in row {i}"
        )
        out.append(record)
    return out


def read(directory):
    present = sorted(os.listdir(directory))
    require(
        present == sorted(TABLES + ["manifest.json"]), f"file set differs: {present}"
    )
    raw = {}
    for name in present:
        with open(os.path.join(directory, name), "rb") as handle:
            raw[name] = handle.read()
    manifest = strict_json(raw["manifest.json"].decode("utf-8"))
    require(set(manifest) == MANIFEST_KEYS, f"manifest keys differ: {sorted(manifest)}")
    require(manifest["schemaVersion"] == SCHEMA, "unsupported schemaVersion")
    require(manifest["model_status"] == "validated", "model is not declared validated")
    digest = manifest["model_digest"]
    require(
        HEX64.fullmatch(digest) and HEX64.fullmatch(manifest["source_checksum"]), "bad digest"
    )
    require(manifest["capabilities"] == CAPABILITIES, "capabilities differ from v0.1")

    described = {f["name"]: f for f in manifest["files"]}
    require(len(described) == len(manifest["files"]), "duplicate file descriptor")
    require(sorted(described) == sorted(TABLES), "described files differ")
    tables = {}
    for name in TABLES:
        d = described[name]
        require(
            set(d) == {"name", "sha256", "bytes", "format", "table"},
            f"{name}: descriptor keys",
        )
        require(d["format"] == "quoted-tsv/v1", f"{name}: format")
        require(
            count(d["bytes"], f"{name} bytes") == len(raw[name]), f"{name}: byte length"
        )
        require(hashlib.sha256(raw[name]).hexdigest() == d["sha256"], f"{name}: sha256")
        require(d["table"]["status"] == "present", f"{name}: table not present")
        text = raw[name].decode("utf-8")
        require(not text.startswith("﻿"), f"{name}: BOM")
        tables[name[:-4]] = read_table(name, text, d["table"], digest)

    # Joins. Node identity is (family, node_id): the model's entity, situation, context and
    # segment identifiers are distinct types, so one string may lawfully name one node of each.
    # Every reference resolves inside the family its column or layer declares.
    family = {
        "entity": "entity",
        "event": "situation",
        "state": "situation",
        "context": "context",
        "segment": "segment",
    }
    nodes = {}
    for n in tables["nodes"]:
        require(n["node_kind"] in family, f"unknown node kind {n['node_kind']}")
        key = (family[n["node_kind"]], n["node_id"])
        require(key not in nodes, f"duplicate node {key}")
        nodes[key] = n

    def kind_of(fam, node_id):
        return nodes.get((fam, node_id), {}).get("node_kind")

    for fam, table in [
        ("entity", "entities"),
        ("situation", "situations"),
        ("context", "contexts"),
        ("segment", "segments"),
    ]:
        ids = sorted(r["node_id"] for r in tables[table])
        expected = sorted(i for (f, i) in nodes if f == fam)
        require(ids == expected, f"{table}: rows differ from {fam} nodes")
    for s in tables["situations"]:
        require(
            s["situation_kind"] == kind_of("situation", s["node_id"]),
            "situation kind mismatch",
        )
    for (fam, i), n in nodes.items():
        c = n["context"]
        if fam == "situation":
            require(
                c["status"] == "present" and (("context", c["value"]) in nodes),
                f"{i}: situation context does not name a context",
            )
        elif fam == "context":
            require(
                c == {"status": "absent", "reason": "root-context"}
                or (c["status"] == "present" and ("context", c["value"]) in nodes),
                f"{i}: context parent does not name a context",
            )
        else:
            require(
                c == {"status": "not-applicable"},
                f"{i}: context must be not-applicable",
            )

    ends = {
        "participant": ("situation", "entity"),
        "temporal": ("situation", "situation"),
        "causal": ("situation", "situation"),
        "goal": ("situation", "situation"),
        "state-change": ("situation", "situation"),
        "reference": ("situation", "situation"),
        "entity-relation": ("entity", "entity"),
    }
    for r in tables["relations"]:
        require(r["layer"] in ends, f"unknown layer {r['layer']}")
        frm, to = ends[r["layer"]]
        require((frm, r["from"]) in nodes, f"{r['claim_id']}: from")
        require((to, r["to"]) in nodes, f"{r['claim_id']}: to")
        if r["layer"] == "state-change":
            require(kind_of("situation", r["from"]) == "event", f"{r['claim_id']}: from")
            require(kind_of("situation", r["to"]) == "state", f"{r['claim_id']}: to")
        require(
            r["relation"]["status"] in ("standard", "custom"),
            f"{r['claim_id']}: relation term",
        )
        context = r["context"]
        if r["layer"] == "temporal":
            require(
                context["status"] == "present" and ("context", context["value"]) in nodes,
                f"{r['claim_id']}: temporal context",
            )
        else:
            require(context == {"status": "not-applicable"}, f"{r['claim_id']}: context")
    for c in tables["circumstances"]:
        require(("situation", c["situation"]) in nodes, "circumstance situation")
    for h in tables["hierarchy"]:
        require(h["member_kind"] in ("situation", "segment"), "hierarchy member kind")
        require((h["member_kind"], h["member"]) in nodes, "hierarchy member")
        require(("segment", h["parent"]) in nodes, "hierarchy parent")

    claims = (
        [n["claim_id"] for n in tables["nodes"]]
        + [r["claim_id"] for r in tables["relations"]]
        + [c["claim_id"] for c in tables["circumstances"]]
        + [h["claim_id"] for h in tables["hierarchy"]]
    )
    require(len(claims) == len(set(claims)), "a claim id appears twice across tables")
    claims = set(claims)
    evidence = {}
    for e in tables["evidence"]:
        require(e["claim_id"] in claims, f"evidence for unknown claim {e['claim_id']}")
        key = (e["claim_id"], e["evidence_index"])
        require(key not in evidence, f"duplicate evidence {key}")
        evidence[key] = e
    # Upstream claims may lawfully be claims these tables omit (descriptors, hypotheses,
    # resolved-value claims). Classify each reference; never reject it and never count it joined.
    upstream = {"in_tables": 0, "outside_tables": 0}
    for e in evidence.values():
        u = e["upstream"]
        require(u["status"] == "present" and isinstance(u["value"], list), "upstream cell")
        ids = u["value"]
        require(all(isinstance(i, str) and i for i in ids), "upstream ids must be strings")
        require(ids == sorted(set(ids)), "upstream ids must be sorted and unique")
        for i in ids:
            upstream["in_tables" if i in claims else "outside_tables"] += 1
    cited = {}
    for s in tables["spans"]:
        require(s["claim_id"] in claims, f"span for unknown claim {s['claim_id']}")
        require(
            0 <= s["utf16_start"] < s["utf16_end_exclusive"],
            f"{s['claim_id']}: empty span",
        )
        if s["origin"] == "claim-evidence":
            require(
                s["evidence_index"]["status"] == "present",
                "evidence span without an index",
            )
            key = (s["claim_id"], s["evidence_index"]["value"])
            require(key in evidence, f"span cites missing evidence {key}")
            cited[key] = cited.get(key, 0) + 1
        else:
            require(s["origin"] == "node-support", f"unknown span origin {s['origin']}")
            require(
                s["evidence_index"] == {"status": "not-applicable"},
                "support span with an index",
            )
    for key, e in evidence.items():
        require(
            cited.get(key, 0) == e["span_count"],
            f"{key}: span_count differs from spans",
        )

    for table in ("nodes", "relations", "circumstances", "hierarchy"):
        for r in tables[table]:
            require(r["claim_status"] in STATUSES, f"{r['claim_id']}: claim_status")
    for sgm in tables["segments"]:
        require(sgm["level"] >= 0, f"{sgm['node_id']}: negative level")
    # Every exported claim carries at least one evidence item, indexed 0..n-1.
    indices = {}
    for (claim, index) in evidence:
        indices.setdefault(claim, []).append(index)
    for claim in claims:
        got = sorted(indices.get(claim, []))
        require(got and got == list(range(len(got))), f"{claim}: evidence indices {got}")
    # Node support belongs to nodes and circumstances; relations and containment have none.
    supported = {n["claim_id"] for n in tables["nodes"]} | {
        c["claim_id"] for c in tables["circumstances"]
    }
    for sp in tables["spans"]:
        if sp["origin"] == "node-support":
            require(sp["claim_id"] in supported, f"{sp['claim_id']}: node support on an edge")
    # Losses and claim accounting.
    exported = count(manifest["exported_claims"], "exported_claims")
    require(exported == len(claims), "exported_claims differs from the tables")
    model_claims = count(manifest["model_claims"], "model_claims")
    losses = {}
    for loss in manifest["losses"]:
        name = loss["structure"]
        require(name not in losses, f"duplicate loss record {name}")
        if loss["status"] == "dropped":
            require(
                set(loss) == {"status", "structure", "dropped", "claims", "reason"},
                name,
            )
            count(loss["dropped"], name)
        else:
            require(loss["status"] == "not-supplied", f"{name}: unknown loss status")
            require(set(loss) == {"status", "structure", "claims", "reason"}, name)
        count(loss["claims"], name)
        losses[name] = loss
    require(
        set(losses) == LOSSES, f"loss records differ: {sorted(set(losses) ^ LOSSES)}"
    )
    require(
        exported + sum(int(l["claims"]) for l in losses.values()) == model_claims,
        "claim accounting: exported + lost differs from model_claims",
    )
    require(
        losses["claim-credence-provenance"]["dropped"] == str(exported),
        "credence loss count",
    )
    require(
        losses["evidence-extractors"]["dropped"] == str(len(evidence)),
        "extractor loss count",
    )

    kinds = {}
    for n in nodes.values():
        kinds[n["node_kind"]] = kinds.get(n["node_kind"], 0) + 1
    return {
        "model_digest": digest,
        "nodes": len(nodes),
        "node_kinds": dict(sorted(kinds.items())),
        "relations": len(tables["relations"]),
        "hierarchy": len(tables["hierarchy"]),
        "evidence": len(evidence),
        "spans": len(tables["spans"]),
        "exported_claims": exported,
        "model_claims": model_claims,
        "upstream_refs": upstream,
        "not_supplied": sorted(
            n for n, l in losses.items() if l["status"] == "not-supplied"
        ),
    }, tables


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        summary, _ = read(argv[1])
    except (
        Refusal,
        KeyError,
        TypeError,
        UnicodeDecodeError,
        ValueError,
        csv.Error,
        json.JSONDecodeError,
    ) as e:
        print(json.dumps({"status": "refused", "reason": str(e)}), file=sys.stderr)
        return 1
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
