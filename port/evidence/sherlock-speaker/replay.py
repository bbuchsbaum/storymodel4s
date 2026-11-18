"""Replay each blind attribution as two single-credit hypotheses at the same source row."""
import json
import sys
from collections import Counter
from pathlib import Path

import assay
from storymodel import (
    fidelity_prompt,
    make_account,
    prepare_fidelity,
    resolve_fidelity,
    verify_fidelity,
)
from storymodel.model import (
    Binding,
    Detail,
    Document,
    Hypothesis,
    Inventory,
    Reference,
    Span,
    Support,
    Unit,
)
from storymodel.serialization import canonical


def unit(document, kind):
    full = Span(start=0, end=assay.utf16(document.text))
    return Unit(kind=kind, support=Support(document=document.id, spans=(full,)))


def replay(key, arm, text, reading, candidate):
    source = Document.create(key, "source", text)
    target = unit(source, "row")
    claim = (
        f"{candidate} is the credited speaker during this segment."
        if arm == "credited"
        else f"{candidate} speaks during this segment."
    )
    recall = Document.create("constructed-probe", "recall", claim)
    probe = unit(recall, "recall")
    detail = Detail(
        unit=probe.id, kind="participant", content=claim, support=probe.support, address="target"
    )
    ref = Reference(
        status="attempted",
        outcome="resolved",
        hypotheses=(Hypothesis(kind="single", targets=(target.id,)),),
    )
    account = make_account(
        (source, recall),
        units=(target, probe),
        details=(detail,),
        inventories=(Inventory(unit=probe.id, status="completed"),),
        bindings=(Binding(detail=detail.id, reference=ref),),
        export_permission="denied",
    )
    request = prepare_fidelity(account, probe.id)
    choice = reading["choice"]
    if choice == candidate:
        grade = "supported"
    elif choice is not None and arm == "credited":
        grade = "contradicted"
    else:
        # Description-only evidence for one speaker does not exclude the other.
        grade = "unverifiable"
    assessment = dict(
        hypothesis="a0",
        status="completed",
        grade=grade,
        reason="Derived from the evidence-limited single-speaker reading.",
    )
    if reading["evidence"] and grade != "unverifiable":
        span = Span.model_validate(reading["evidence"])
        assessment["evidence"] = [
            dict(document=source.id, span=span.model_dump(), text=span.slice(source.text))
        ]
    raw = canonical(
        dict(
            schema_version="storymodel.fidelity-proposal/v1",
            packet_sha256=json.loads(fidelity_prompt(request))["packet_sha256"],
            assessments=[assessment],
        )
    )
    result = resolve_fidelity(request, raw)
    verify_fidelity(request, raw, result)
    assert result.decisions[0].status == "admitted"
    after = result.account
    assert (after.details, after.units) == (account.details, account.units)
    assert after.bindings[0].reference == ref
    assert after.bindings[0].assessments[0].grade == grade
    return grade


if __name__ == "__main__":
    readings = json.loads(Path(sys.argv[1]).read_text())
    assay.score(readings)
    totals = {arm: Counter() for arm in assay.ARMS}
    for key, (arm, text, _) in assay.trials().items():
        for candidate in assay.PEOPLE:
            totals[arm][replay(key, arm, text, readings[key], candidate)] += 1
    print(json.dumps(totals, sort_keys=True))
