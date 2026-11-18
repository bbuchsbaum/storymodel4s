"""Replay a saved plan through a provenance-bound caption-lexical decoder.

The method deliberately exposes retrieval, local costs, and sequence decoding as
separate artifacts.  Its lexical scores are engineering inputs, not claims of
semantic accuracy or calibrated reference confidence.
"""

from __future__ import annotations

from hashlib import sha256
from pathlib import Path
from typing import Literal, Self

from pydantic import model_validator

from ._lexical import stem_set
from .analysis_workflow import AnalysisPlan, AnalysisReplay, replay_analysis
from .local_evidence import (
    AccountInferenceResult,
    LocalCell,
    LocalEvidenceSpec,
    RecallSelection,
    RetrievalRow,
    SemanticCell,
    SemanticEstimate,
    derive_local_evidence,
    infer_accounts,
)
from .model import Digest, Model, _validate_instance, text_support
from .rich_account import SupportedAccount
from .sequence import SequenceResult
from .serialization import canonical, digest, strict_json


def _distance(left: frozenset[str], right: frozenset[str]) -> float:
    union = left | right
    return 1 - len(left & right) / len(union) if union else 1.0


class CaptionEvidence(Model):
    """Caption text bound to one source snapshot; blank text is an unavailable channel."""

    source_sha256: Digest
    captions: dict[str, str]

    @model_validator(mode="after")
    def has_caption_targets(self) -> Self:
        if not self.captions:
            raise ValueError("caption evidence must name at least one target")
        return self


class CaptionLexicalScore(Model):
    """A caption-text measurement joined to exactly one locally retrieved anchor."""

    unit: str
    target: str
    caption_distance: float | None
    lexical_distance: float
    blend: float
    blended_distance: float | None


class AlignmentMethodResult(Model):
    """One plan-bound lexical run, with each inferential stage left inspectable."""

    schema_version: Literal["storymodel.alignment-method/v1"] = "storymodel.alignment-method/v1"
    blend: float
    max_candidates: int
    max_postings: int
    plan_sha256: Digest
    source_sha256: Digest
    recall_sha256: Digest
    caption_evidence_sha256: Digest
    retrieval: tuple[RetrievalRow, ...]
    caption_scores: tuple[CaptionLexicalScore, ...]
    local_ranking: tuple[LocalCell, ...]
    decoding: SequenceResult


def _recall_before_pairing(replay: AnalysisReplay) -> SupportedAccount:
    """Use the recall branch after its saved detail stages, before source pairing."""
    return (
        replay.recall_details[-1].result.account if replay.recall_details else replay.recall.account
    )


def _ordered_selections(recall: SupportedAccount) -> tuple[RecallSelection, ...]:
    """Use occurrence order, never account serialization order, for decoder rows."""

    ordered: list[tuple[int, str]] = []
    documents = set()
    starts = set()
    for unit in recall.units:
        if unit.kind != "recall":
            continue
        support = text_support(unit.support)
        documents.add(support.document)
        if len(documents) > 1:
            raise ValueError("caption decoding requires one recall document")
        start = support.spans[0].start
        if start in starts:
            raise ValueError("tied recall starts require an explicit sequence order")
        starts.add(start)
        ordered.append((start, unit.id))
    return tuple(
        RecallSelection(unit=unit_id, content="text-only") for _, unit_id in sorted(ordered)
    )


def run_alignment_method(
    plan_path: Path,
    captions: CaptionEvidence,
    *,
    blend: float = 0.5,
    max_candidates: int = 32,
    max_postings: int = 4096,
) -> AlignmentMethodResult:
    """Run caption-lexical retrieval pricing and sparse decoding from a saved plan.

    Captions are an explicit source-digest-bound sidecar, never relabelled source
    situation evidence. They are combined with the Scala ``CaptionScoring`` formula
    ``(1 - blend) * lexical + blend * caption``.  Empty support text is an
    unavailable caption, not a perfect match.  Every recall unit is queried in
    textual occurrence order and as ``text-only``, avoiding an implicit choice
    among multiple recall graph situations.
    """
    captions = _validate_instance(CaptionEvidence, captions)
    if not 0.0 <= blend <= 1.0:
        raise ValueError("caption blend must be in [0, 1]")
    raw_plan = plan_path.read_bytes()
    plan = AnalysisPlan.model_validate_json(canonical(strict_json(raw_plan)))
    replay = replay_analysis(plan, plan_path.parent)
    recall = _recall_before_pairing(replay)
    source_sha256 = digest(replay.source.account.model_dump(mode="json"))
    if captions.source_sha256 != source_sha256:
        raise ValueError("caption evidence is bound to a different source account")
    selections = _ordered_selections(recall)
    if not selections:
        raise ValueError("analysis plan recall branch has no recall units for lexical alignment")
    baseline_spec = LocalEvidenceSpec(
        units=selections,
        max_candidates=max_candidates,
        max_postings=max_postings,
        semantic="lexical",
    )
    candidates = derive_local_evidence(replay.source.account, recall, baseline_spec)
    recall_docs = {document.id: document.text for document in recall.documents}
    known_targets = {node.anchor for node in candidates.sequence.projection.nodes}
    unknown = captions.captions.keys() - known_targets
    if unknown:
        raise ValueError("caption evidence names a target outside the checked source")
    recall_terms = {}
    for unit in recall.units:
        support = text_support(unit.support)
        surface = " ".join(span.slice(recall_docs[support.document]) for span in support.spans)
        recall_terms[unit.id] = stem_set(surface)
    scores = []
    semantic_cells = []
    for cell in candidates.cells:
        unit_terms = recall_terms[cell.unit]
        caption = captions.captions.get(cell.target, "")
        lexical = cell.terms["semantic"]
        caption_distance = _distance(unit_terms, stem_set(caption)) if caption.strip() else None
        blended = (
            (1 - blend) * lexical + blend * caption_distance
            if caption_distance is not None
            else None
        )
        scores.append(
            CaptionLexicalScore(
                unit=cell.unit,
                target=cell.target,
                caption_distance=caption_distance,
                lexical_distance=lexical,
                blend=blend,
                blended_distance=blended,
            )
        )
        semantic_cells.append(
            SemanticCell(
                unit=cell.unit,
                target=cell.target,
                estimate=(
                    SemanticEstimate(status="observed", value=blended)
                    if blended is not None
                    else SemanticEstimate(status="missing", reason="caption-unavailable")
                ),
            )
        )
    inference: AccountInferenceResult = infer_accounts(
        replay.source.account,
        recall,
        LocalEvidenceSpec(
            units=selections,
            max_candidates=max_candidates,
            max_postings=max_postings,
            semantic="table",
            semantic_cells=tuple(semantic_cells),
        ),
    )
    if candidates.retrieval != inference.evidence.retrieval:
        raise ValueError("caption pricing changed the retrieved candidate population")
    occurrence = {selection.unit: index for index, selection in enumerate(selections)}
    local_ranking = tuple(
        sorted(
            inference.evidence.cells,
            key=lambda cell: (
                occurrence[cell.unit],
                cell.excluded,
                cell.total if cell.total is not None else float("inf"),
                cell.target,
            ),
        )
    )
    return AlignmentMethodResult(
        blend=blend,
        max_candidates=max_candidates,
        max_postings=max_postings,
        plan_sha256=sha256(raw_plan).hexdigest(),
        source_sha256=source_sha256,
        recall_sha256=digest(recall.model_dump(mode="json")),
        caption_evidence_sha256=digest(captions.model_dump(mode="json")),
        retrieval=inference.evidence.retrieval,
        caption_scores=tuple(scores),
        local_ranking=local_ranking,
        decoding=inference.inference,
    )
