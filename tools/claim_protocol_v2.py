#!/usr/bin/env python3
"""Explicit wire-equivalence replay; original model output is never overwritten.

This is a post-hoc development condition, not a new inference or an accuracy fix.
Only singleton reason objects are equivalent to the v1 string representation.
All original identity, duplicate-key, enum and quotation checks still apply.
"""
import json
import claim_assessment_probe as v1

VERSION = "claim-reason-wire-equivalence/v2"


def canonicalize(request, raw):
    value = json.loads(raw, object_pairs_hook=v1.unique_object)
    changed = 0
    if isinstance(value, dict) and isinstance(value.get("unresolved"), list):
        reasons = []
        for reason in value["unresolved"]:
            if isinstance(reason, dict) and set(reason) == {"reason"}:
                reason = reason["reason"]
                if not isinstance(reason, str) or reason not in v1.REASONS:
                    raise ValueError("invalid object reason")
                changed += 1
            reasons.append(reason)
        value["unresolved"] = reasons
    canonical = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return canonical, v1.validate_proposal(request, canonical), changed


def replay(request, output):
    """Canonical raw is explicitly derivative; retain exact original raw and status."""
    if (output["question"], output["arm"], output["requestIdentity"]) != (
            request["question"], request["arm"], request["identity"]):
        raise ValueError("output does not belong to request")
    result = dict(output, originalRaw=output.get("raw"),
                  originalProcessing=output["processing"], normalization=VERSION)
    result["convertedReasons"] = 0
    if output["processing"] not in ("valid", "invalid_proposal"):
        return result
    try:
        raw, proposal, count = canonicalize(request, output["raw"])
        result.update(raw=raw, proposal=proposal, processing="valid", convertedReasons=count)
    except (ValueError, TypeError, KeyError):
        result.update(processing="invalid_proposal", proposal=None)
    return result
