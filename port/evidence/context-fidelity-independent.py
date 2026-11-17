"""Reproduce the independent contextual-fidelity probes against the reviewed commit.

Run with port/.venv/bin/python. Both source and fixture helpers come from the
pinned commit; expected acceptance decisions are specified in this probe.
"""

import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "port/data/context-fidelity-independent"
SHA = "5a98a3d5920923e02627eb780005b0ffa5400918"


def run_child():
    from test_context_fidelity import (
        contextual_assessment,
        contextual_reply,
        contextual_request,
        quote,
    )

    from storymodel.context_fidelity import ContextEvidence
    from storymodel.fidelity import (
        fidelity_prompt,
        fidelity_request_digest,
        prepare_fidelity,
        resolve_fidelity,
        verify_fidelity,
    )
    from storymodel.model import Event, Span, Support
    from storymodel.serialization import canonical, digest

    results = {}
    r, target = contextual_request(unresolved_recall_holder=True)
    v = json.loads(fidelity_prompt(r))["context_evidence"]["a0"]
    h = next(c["key"] for c in v["contexts"] if c["side"] == "recall" and c["holder"]["applicable"])
    m = next(m["key"] for m in v["mentions"] if not m["identity"]["available"])
    count = 0
    for required in ({"required_holders": [h]}, {"required_mentions": [m]}):
        accounting = {
            "considered_contexts": [c["key"] for c in v["contexts"]],
            "required_holders": [],
            "required_mentions": [],
            "reason": "material dependency",
            **required,
        }
        for grade in (
            "supported",
            "gist-detail-error",
            "contradicted",
            "recaller-inference",
            "invented",
            "misread",
            "unverifiable",
        ):
            extra = {"severity": "minor"} if grade == "gist-detail-error" else {}
            row = contextual_assessment(
                r, grade=grade, evidence=[quote(target)], context_accounting=accounting, **extra
            )
            result = resolve_fidelity(r, contextual_reply(r, row))
            assert result.decisions[0].status == (
                "admitted" if grade == "unverifiable" else "rejected"
            )
            count += 1
        for status in ("pending", "unavailable", "refused"):
            row = contextual_assessment(r, status=status, context_accounting=accounting)
            assert resolve_fidelity(r, contextual_reply(r, row)).decisions[0].status == "admitted"
            count += 1
    results["dependency_status_grade_matrix"] = count

    r, target = contextual_request()
    v = json.loads(fidelity_prompt(r))["context_evidence"]["a0"]
    classes = {m["side"]: m["identity"]["referent"] for m in v["mentions"]}
    assert classes["source"] != classes["recall"]
    results["same_label_cross_document_identity_distinct"] = True
    row = contextual_assessment(r, evidence=[quote(target)])
    raw = contextual_reply(r, row)
    receipt = resolve_fidelity(r, raw)
    assert verify_fidelity(r, raw, receipt) == receipt
    fake = receipt.model_copy(
        update={
            "context_accounting": {
                "a0": receipt.context_accounting["a0"].model_copy(update={"reason": "tampered"})
            }
        }
    )
    try:
        verify_fidelity(r, raw, fake)
    except ValueError:
        pass
    else:
        raise AssertionError("tampered accounting receipt verified")
    results["receipt_tamper_refused"] = True

    changed_evidence = r.context_evidence.model_copy(
        update={"holder_proposals": r.context_evidence.holder_proposals + "\r\n"}
    )
    changed = r.model_copy(update={"context_evidence": changed_evidence})
    assert fidelity_prompt(changed) == fidelity_prompt(r)
    assert fidelity_request_digest(changed) != fidelity_request_digest(r)
    try:
        verify_fidelity(changed, raw, receipt)
    except ValueError:
        pass
    else:
        raise AssertionError("exact raw substitution verified")
    results["raw_whitespace_bound_but_hidden_from_prompt"] = True

    # A source situation that overlaps the target but extends beyond it must not be selected.
    source_event = next(e for e in r.account.events if e.predicate == "target-only")
    wide = Event(
        side=source_event.side,
        context=source_event.context,
        predicate=source_event.predicate,
        participants=source_event.participants,
        evidence=(Support(document=target.support.document, spans=(Span(start=0, end=11),)),),
    )
    account = r.account.model_copy(
        update={"events": tuple(wide if e.id == source_event.id else e for e in r.account.events)}
    )
    sha = digest(account.model_dump(mode="json"))
    evidence = ContextEvidence(
        **{
            field: canonical(
                json.loads(getattr(r.context_evidence, field)) | {"account_sha256": sha}
            )
            for field in ("identity_annotations", "holder_proposals")
        }
    )
    wide_request = prepare_fidelity(account, r.unit, context_evidence=evidence)
    wide_view = json.loads(fidelity_prompt(wide_request))["context_evidence"]["a0"]
    assert wide_view["source_placement"] == "no-complete-scoped-situation"
    assert {s["side"] for s in wide_view["situations"]} == {"recall"}
    results["partial_source_overlap_excluded"] = True

    # Physical CRLF input must survive the new CLI flags exactly.
    (OUT / "account.json").write_text(canonical(r.account.model_dump(mode="json")))
    for field in ("identity_annotations", "holder_proposals"):
        (OUT / f"{field}.json").write_bytes(
            (json.dumps(json.loads(getattr(r.context_evidence, field)), indent=2) + "\n")
            .replace("\n", "\r\n")
            .encode()
        )
    args = [
        sys.executable,
        "-m",
        "storymodel.cli",
        "prepare-fidelity",
        str(OUT / "account.json"),
        r.unit,
        "--identity-annotations",
        str(OUT / "identity_annotations.json"),
        "--holder-proposals",
        str(OUT / "holder_proposals.json"),
    ]
    p = subprocess.run(args, capture_output=True, text=True)
    assert p.returncode == 0, p.stderr
    actual = json.loads(p.stdout)
    (OUT / "cli-request.json").write_text(p.stdout)
    results["CRLF_exact_recording"] = {
        field: actual["context_evidence"][field].encode() == (OUT / f"{field}.json").read_bytes()
        for field in ("identity_annotations", "holder_proposals")
    }
    results["candidate"] = SHA
    (OUT / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results, indent=2))
    assert all(results["CRLF_exact_recording"].values()), (
        "BLOCK: CLI normalizes exact raw recordings"
    )


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    if "--child" in sys.argv:
        run_child()
        return
    with tempfile.TemporaryDirectory(prefix="context-fidelity-review-") as temp:
        extracted = Path(temp)
        raw = subprocess.check_output(["git", "archive", SHA, "port/src", "port/tests"], cwd=ROOT)
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            archive.extractall(extracted, filter="data")
        env = dict(
            os.environ,
            PYTHONPATH=os.pathsep.join(
                [str(extracted / "port/src"), str(extracted / "port/tests")]
            ),
        )
        run = subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--child"],
            env=env,
            capture_output=True,
            text=True,
            cwd=extracted,
        )
        (OUT / "probe.log").write_text(run.stdout + run.stderr)
        print(run.stdout + run.stderr, end="")
        raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
