"""Replay a saved plan through a provenance-bound caption-lexical decoder.

The method deliberately exposes retrieval, local costs, and sequence decoding as
separate artifacts.  Its lexical scores are engineering inputs, not claims of
semantic accuracy or calibrated reference confidence.
"""

from __future__ import annotations

from hashlib import sha256
from pathlib import Path
from typing import Literal, Self

from pydantic import Field, model_validator

from ._base import Name
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
from .playback import PlaybackSupport, TickInterval
from .prepared_construction import PreparedConstruction, PreparedConstructionResult
from .rich_account import SupportedAccount
from .sequence import SequenceResult
from .serialization import canonical, digest, strict_json
from .sherlock_composition import _native_row_supports
from .sherlock_source import SherlockSource


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


class NativeCaption(Model):
    """An independent text observation declared over one physical stream's native ticks."""

    source: Name
    interval: TickInterval
    text: str


class NativeCaptionEvidence(Model):
    """Ordered caption declarations for the checked native axes of a source account."""

    schema_version: Literal["storymodel.native-captions/v1"] = "storymodel.native-captions/v1"
    source_sha256: Digest
    captions: tuple[NativeCaption, ...] = Field(min_length=1)


def _match_native_captions(
    rows: dict[str, PlaybackSupport], captions: tuple[NativeCaption, ...]
) -> tuple[str, ...]:
    """Bind each caption exactly once. Rows without a caption are legitimate."""
    observations: dict[tuple[str, str, str], list[int]] = {}
    for index, caption in enumerate(captions):
        key = (caption.source, caption.interval.start, caption.interval.end)
        observations.setdefault(key, []).append(index)
    targets: dict[int, str] = {}
    reused = False
    for target, support in rows.items():
        if len(support.intervals) == 1 and not support.points:
            interval = support.intervals[0]
            hits = observations.get((support.source, interval.start, interval.end), [])
            if len(hits) > 1:
                raise ValueError("ambiguous caption support")
            if hits:
                index = hits[0]
                reused |= index in targets
                targets[index] = target
    if reused or len(targets) != len(captions):
        raise ValueError("caption has no unique exact target")
    return tuple(targets[i] for i in range(len(captions)))


def _native_targets(replay: AnalysisReplay, captions: NativeCaptionEvidence) -> tuple[str, ...]:
    trace = replay.source
    request, result = trace.request, trace.construction
    if not (
        isinstance(request, PreparedConstruction)
        and isinstance(request.intake, SherlockSource)
        and isinstance(result, PreparedConstructionResult)
    ):
        raise ValueError("native caption alignment requires prepared Sherlock source rows")
    _, supports = _native_row_supports(request.intake)
    # The row owns its physical interval. A state extracted from that row may cover less.
    rows = {row.unit: supports[row.row] for row in result.construction.resolutions}
    return _match_native_captions(rows, captions.captions)


class AlignmentMethodResult(Model):
    """One plan-bound lexical run, with each inferential stage left inspectable."""

    schema_version: Literal["storymodel.alignment-method/v1", "storymodel.alignment-method/v2"] = (
        "storymodel.alignment-method/v1"
    )
    # In input order, including captions on rows that retrieval did not nominate.
    native_caption_targets: tuple[str, ...] | None = Field(
        default=None, exclude_if=lambda x: x is None
    )
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

    @model_validator(mode="after")
    def caption_profile(self) -> Self:
        if self.schema_version.endswith("/v2") != (self.native_caption_targets is not None):
            raise ValueError("native caption targets require alignment method v2")
        return self


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
    captions: CaptionEvidence | NativeCaptionEvidence,
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
    if isinstance(captions, NativeCaptionEvidence):
        captions = _validate_instance(NativeCaptionEvidence, captions)
    else:
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
    native_targets = None
    if isinstance(captions, NativeCaptionEvidence):
        native_targets = _native_targets(replay, captions)
        texts = {
            target: caption.text
            for target, caption in zip(native_targets, captions.captions, strict=True)
        }
    else:
        texts = captions.captions
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
    unknown = texts.keys() - known_targets
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
        caption = texts.get(cell.target, "")
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
        schema_version="storymodel.alignment-method/v2"
        if native_targets is not None
        else "storymodel.alignment-method/v1",
        native_caption_targets=native_targets,
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
