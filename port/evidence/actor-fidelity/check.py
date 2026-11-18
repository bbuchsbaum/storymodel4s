"""Build the fixed-reference actor contrast and replay a separately recorded machine reading."""
import argparse
import json
import random
from collections import Counter
from pathlib import Path

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

HERE = Path(__file__).resolve().parent
FIXTURE = HERE.parents[2] / "tools/recall-study/fixtures/baseline-miniatures.json"
GROUPS = {
    "stated": {"supported"},
    "exchanged": {"contradicted", "gist-detail-error"},
    "withheld": {"unverifiable", "unavailable"},
}


def packet(source_rows, target_index, recall_text):
    source = Document.create("source", "source", "\n".join(source_rows))
    recall = Document.create("recall", "recall", recall_text)

    def support(doc, offset, text):
        # The admitted stimulus is ASCII, so these are also UTF-16 offsets.
        assert text.isascii()
        return Support(document=doc.id, spans=(Span(start=offset, end=offset + len(text)),))

    scene = Unit(kind="scene", support=support(source, 0, source.text))
    rows = tuple(
        Unit(kind="row", parent=scene.id, support=support(source, start, text))
        for start, text in zip((0, len(source_rows[0]) + 1), source_rows, strict=True)
    )
    unit = Unit(kind="recall", support=support(recall, 0, recall.text))
    detail = Detail(
        unit=unit.id,
        kind="participant",
        content=recall.text,
        support=unit.support,
        address="target",
    )
    target = rows[target_index]
    reference = Reference(
        status="attempted",
        outcome="resolved",
        hypotheses=(Hypothesis(kind="single", targets=(target.id,)),),
    )
    account = make_account(
        (source, recall),
        units=(scene, *rows, unit),
        details=(detail,),
        inventories=(Inventory(unit=unit.id, status="completed"),),
        bindings=(Binding(detail=detail.id, reference=reference),),
        export_permission="allowed",
    )
    return prepare_fidelity(account, unit.id), source


def cases():
    events = json.loads(FIXTURE.read_text())["textSource"]["events"]
    assert len(events) == 8
    result = {}
    for i, event in enumerate(events):
        text = event["text"]
        foil = events[i ^ 1]["text"]
        _, actor, action = text.split(" ", 2)
        _, foil_actor, _ = foil.split(" ", 2)
        assert actor != foil_actor
        for condition in GROUPS:
            rendered = "Someone " + action if condition == "withheld" else text
            recall_actor = foil_actor if condition == "exchanged" else actor
            # Identical determiner change in every condition.
            recall = "The " + recall_actor + " " + action
            source = [rendered, foil] if i % 2 == 0 else [foil, rendered]
            request, document = packet(source, i % 2, recall)
            prompt = json.loads(fidelity_prompt(request))
            key = prompt["packet_sha256"]
            assert key not in result
            result[key] = (prompt, request, document, i, condition)
    return result


def write_packets(path):
    prompts = [v[0] for v in cases().values()]
    random.Random(20261117).shuffle(prompts)
    with path.open("x") as out:
        json.dump(prompts, out)


def score(path):
    trials = cases()
    records = json.loads(path.read_text())
    by_key = {r["packet_sha256"]: r for r in records}
    if len(records) != len(by_key) or set(by_key) != set(trials):
        raise ValueError("duplicate, missing, or unregistered packet")
    counts = {k: Counter() for k in GROUPS}
    recovered = {k: set() for k in GROUPS}
    for key, (_, request, document, event, condition) in trials.items():
        r = by_key[key]
        assessment = dict(hypothesis="a0", status=r["status"], reason=r["reason"])
        if r["status"] == "completed":
            assessment["grade"] = r["grade"]
        if r.get("severity") is not None:
            assessment["severity"] = r["severity"]
        if r.get("evidence_span") is not None:
            span = Span.model_validate(r["evidence_span"])
            assessment["evidence"] = [
                dict(document=document.id, span=span.model_dump(), text=span.slice(document.text))
            ]
        raw = canonical(
            dict(
                schema_version="storymodel.fidelity-proposal/v1",
                packet_sha256=key,
                assessments=[assessment],
            )
        )
        replay = resolve_fidelity(request, raw)
        verify_fidelity(request, raw, replay)
        before, after = request.account, replay.account
        assert before.bindings[0].reference == after.bindings[0].reference
        assert before.details == after.details and before.units == after.units
        if replay.decisions[0].status == "admitted":
            grade = after.bindings[0].assessments[0]
            outcome = grade.grade if grade.status == "completed" else grade.status
        else:
            outcome = "rejected"
        counts[condition][outcome] += 1
        if outcome in GROUPS[condition]:
            recovered[condition].add(event)
    return dict(
        outcomes=counts,
        correct={k: len(v) for k, v in recovered.items()},
        complete_triplets=len(set.intersection(*recovered.values())),
        scene_only_always_supported=sum(v[-1] == "stated" for v in trials.values()),
        total=len(trials),
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("prepare").add_argument("output", type=Path)
    sub.add_parser("score").add_argument("responses", type=Path)
    args = parser.parse_args()
    if args.mode == "prepare":
        write_packets(args.output)
    else:
        print(json.dumps(score(args.responses), indent=2))
