"""A hand-computable saved-plan witness for the lexical alignment method."""

from __future__ import annotations

from pathlib import Path

import pytest

from storymodel.accounts import make_account
from storymodel.alignment_method import CaptionEvidence, run_alignment_method
from storymodel.analysis_workflow import AnalysisAccountInput, AnalysisFile, AnalysisPlan
from storymodel.model import Context, Document, State, Support, Unit
from storymodel.rich_account import RichAccount, validate_rich_account
from storymodel.serialization import canonical, digest


def _account(side: str, text: str) -> RichAccount:
    document = Document.create(f"{side}-hand-computable", side, text)
    root = Context(side=side, kind="narrated")
    # A nonempty span is deliberately explicit: source and recall contain exactly
    # the same single token, so lexical distance and every local mismatch are zero.
    support = Support(document=document.id, spans=({"start": 0, "end": len(text)},))
    units = (Unit(kind="recall", support=support),) if side == "recall" else ()
    state = State(side=side, context=root.id, predicate=text, evidence=(support,))
    seed = make_account((document,), contexts=(root,), export_permission="allowed")
    return validate_rich_account(
        RichAccount(
            documents=(document,),
            units=units,
            contexts=(root,),
            states=(state,),
            recipe=seed.recipe,
            export_permission="allowed",
        )
    )


def _write_plan(root: Path) -> tuple[Path, RichAccount]:
    source, recall = _account("source", "visit"), _account("recall", "visit")
    source_path, recall_path = root / "source.json", root / "recall.json"
    source_path.write_text(source.model_dump_json())
    recall_path.write_text(recall.model_dump_json())
    plan = AnalysisPlan(
        source=AnalysisAccountInput(kind="account", file=AnalysisFile(path=source_path.name)),
        recall=AnalysisAccountInput(kind="account", file=AnalysisFile(path=recall_path.name)),
    )
    path = root / "plan.json"
    path.write_text(canonical(plan.model_dump(mode="json")))
    return path, source


def test_saved_plan_keeps_caption_lexical_stages_separate_and_changes_decoding(
    tmp_path: Path,
) -> None:
    plan, source = _write_plan(tmp_path)
    target = source.states[0].id
    source_sha256 = digest(source.model_dump(mode="json"))
    matching = CaptionEvidence(source_sha256=source_sha256, captions={target: "visit"})
    mismatching = CaptionEvidence(source_sha256=source_sha256, captions={target: "avoid"})
    result = run_alignment_method(plan, matching, blend=1.0)
    changed = run_alignment_method(plan, mismatching, blend=1.0)

    # Hand computation: text-only recall has one stem (visit), which nominates
    # the sole source state with score 1. The matching caption has distance zero;
    # replacing it with "avoid" gives distance one. At blend 1 the latter makes
    # the local source cost exceed external floor 1, changing the Viterbi path.
    retrieval = result.retrieval[0]
    local = result.local_ranking[0]
    assert retrieval.status == "ranked"
    assert retrieval.candidates_seen == retrieval.postings_read == 1
    assert len(retrieval.nominations) == 1 and retrieval.nominations[0].score == 1.0
    caption = result.caption_scores[0]
    assert caption.caption_distance == caption.lexical_distance == caption.blended_distance == 0.0
    assert caption.blend == 1.0 and changed.caption_scores[0].blended_distance == 1.0
    assert local.terms["semantic"] == 0.0 and local.total == pytest.approx(0.47109375)
    assert result.decoding.best_path == (local.state.id,)
    posterior = {mass.state: mass.probability for mass in result.decoding.posterior[0].mass}
    assert max(posterior, key=posterior.__getitem__) == local.state.id
    assert changed.decoding.best_path != result.decoding.best_path

    forged = matching.model_copy(update={"source_sha256": "0" * 64})
    with pytest.raises(ValueError, match="different source"):
        run_alignment_method(plan, forged)


def test_caption_decoder_requires_single_recall_document(tmp_path):
    plan, source = _write_plan(tmp_path)
    path = tmp_path / "recall.json"
    recall = RichAccount.model_validate_json(path.read_bytes())
    other = Document.create("other-recording", "recall", "visit")
    other_unit = Unit(
        kind="recall", support=Support(document=other.id, spans=({"start": 0, "end": 5},))
    )
    expanded = validate_rich_account(
        recall.model_copy(
            update={"documents": (*recall.documents, other), "units": (*recall.units, other_unit)}
        )
    )
    path.write_text(expanded.model_dump_json())
    captions = CaptionEvidence(
        source_sha256=digest(source.model_dump(mode="json")),
        captions={source.states[0].id: "visit"},
    )
    with pytest.raises(ValueError, match="one recall document"):
        run_alignment_method(plan, captions)


def test_sequence_orders_units_by_text_onset(tmp_path):
    plan, source = _write_plan(tmp_path)
    recall = _account("recall", "visit visit")
    # Store the late occurrence first.
    doc = recall.documents[0]

    def word(lo, hi):
        return Unit(
            kind="recall", support=Support(document=doc.id, spans=({"start": lo, "end": hi},))
        )

    early, late = word(0, 5), word(6, 11)
    ordered = validate_rich_account(recall.model_copy(update={"units": (late, early)}))
    (tmp_path / "recall.json").write_text(ordered.model_dump_json())
    sidecar = CaptionEvidence(
        source_sha256=digest(source.model_dump(mode="json")),
        captions={source.states[0].id: "visit"},
    )
    readout = run_alignment_method(plan, sidecar)
    assert [row.unit for row in readout.retrieval] == [early.id, late.id]
    assert [row.unit for row in readout.decoding.posterior] == [early.id, late.id]
