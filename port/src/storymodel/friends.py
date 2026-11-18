"""Friends storyboard/recall intake without opening the retained workbook.

The Scala intake's essential boundary is deliberately small: it reads two
storyboard sheets as distinct segmentations (the 52-event NarrComb and 56-event
MoreEMs scales), each with presentation ``Time`` and original-order
``TimeOrig`` clocks.  Recall-scoring sheets provide a recall *extent* such as
``SecondsOfRecall``; they do not supply word or event onsets.  This adapter
therefore retains both scales and never derives recall timestamps from either
storyboard clock or from a recall duration.

Callers supply already-authorized, decoded rows.  The adapter does not read
``FriendsRecallScoring.xlsx`` and does not interpret the event-scoring codes.
"""

from __future__ import annotations

from typing import Literal, Self

from pydantic import Field, model_validator

from ._admission import Quote, UnresolvedClaim
from .model import Digest, Model, Name, Span, _validate_instance
from .recall_intake import PreparedRecall, prepare_recall
from .serialization import canonical, digest
from .source import SourceRequest, SourceResult, SourceRow, compile_source, prepare_source

FriendsScale = Literal["narrcomb-52", "more-ems-56"]


class FriendsStoryboardRow(Model):
    """One decoded storyboard row; why: scale and clocks remain distinct observations."""

    event: int = Field(gt=0)
    episode: int = Field(gt=0)
    scene: int = Field(gt=0)
    presentation_seconds: int = Field(ge=0)
    original_seconds: int = Field(ge=0)
    surface: Name


class FriendsStoryboard(Model):
    """Both retained Friends event segmentations, never silently collapsed into one."""

    narrcomb_52: tuple[FriendsStoryboardRow, ...] = Field(min_length=1)
    more_ems_56: tuple[FriendsStoryboardRow, ...] = Field(min_length=1)
    source_scale: FriendsScale
    crosswalk_tolerance_seconds: int = Field(default=5, ge=0)
    # Scala's `WhichEvent` is scored against MoreEMs (56), independent of the source scale.
    recall_event_scale: Literal["more-ems-56"] = "more-ems-56"

    @model_validator(mode="after")
    def ordered_unique_scales(self) -> Self:
        for name, rows in (("narrcomb-52", self.narrcomb_52), ("more-ems-56", self.more_ems_56)):
            events = tuple(row.event for row in rows)
            if len(set(events)) != len(events):
                raise ValueError(f"{name} repeats an event number")
            if events != tuple(sorted(events)):
                raise ValueError(f"{name} event numbers must be in ascending order")
        return self

    @property
    def selected(self) -> tuple[FriendsStoryboardRow, ...]:
        """Return only the explicitly selected source scale; the other remains in the receipt."""
        return self.narrcomb_52 if self.source_scale == "narrcomb-52" else self.more_ems_56


class FriendsRecallSheet(Model):
    """One decoded recall sheet with no invented within-recall timing."""

    participant: Name
    raw_text: str = Field(min_length=1)
    seconds_of_recall: int | None = Field(default=None, ge=0)


class FriendsStudy(Model):
    """Authorized tabular Friends input, kept separate from graph construction."""

    storyboard: FriendsStoryboard
    recalls: tuple[FriendsRecallSheet, ...] = Field(min_length=1)
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def unique_participants(self) -> Self:
        if len({sheet.participant for sheet in self.recalls}) != len(self.recalls):
            raise ValueError("Friends intake repeats a participant sheet")
        return self


class FriendsPreparedRecall(Model):
    """A prepared recall plus its observed duration; why: duration is not an onset series."""

    participant: Name
    recall: PreparedRecall
    seconds_of_recall: int | None
    timing_status: Literal["no-within-recall-timestamps-observed"] = (
        "no-within-recall-timestamps-observed"
    )


class FriendsCrosswalkCandidate(Model):
    """One retained 52-scale onset candidate; why: ties are evidence, not a guessed map."""

    event: int = Field(gt=0)
    presentation_seconds: int = Field(ge=0)
    delta_seconds: int = Field(ge=0)


class FriendsCrosswalkEntry(Model):
    """The declared 56-to-52 result for one event, including ambiguity and absence."""

    more_ems_event: int = Field(gt=0)
    presentation_seconds: int = Field(ge=0)
    exact_candidates: tuple[FriendsCrosswalkCandidate, ...]
    tolerance_candidates: tuple[FriendsCrosswalkCandidate, ...]
    target_event: int | None = Field(default=None, gt=0)
    kind: Literal["exact", "tolerance", "ambiguous", "unmatched"]

    @model_validator(mode="after")
    def outcome_matches_evidence(self) -> Self:
        nearest = min((item.delta_seconds for item in self.tolerance_candidates), default=None)
        tied = (
            ()
            if nearest is None
            else tuple(item for item in self.tolerance_candidates if item.delta_seconds == nearest)
        )
        if self.kind == "exact":
            if (
                len(self.exact_candidates) != 1
                or self.target_event != self.exact_candidates[0].event
            ):
                raise ValueError("exact Friends crosswalk entry must name its sole exact target")
        elif self.kind == "tolerance":
            if len(self.exact_candidates) or len(tied) != 1 or self.target_event != tied[0].event:
                raise ValueError(
                    "tolerance Friends crosswalk entry must name its unique nearest target"
                )
        elif self.kind == "ambiguous":
            candidates = self.exact_candidates if self.exact_candidates else tied
            if len(candidates) < 2 or self.target_event is not None:
                raise ValueError("ambiguous Friends crosswalk entry must retain tied candidates")
        elif self.target_event is not None or self.tolerance_candidates:
            raise ValueError("unmatched Friends crosswalk entry cannot name a candidate")
        return self


class FriendsCrosswalk(Model):
    """The receipted 56-to-52 shared-presentation-Time crosswalk."""

    tolerance_seconds: int = Field(ge=0)
    entries: tuple[FriendsCrosswalkEntry, ...] = Field(min_length=1)


class FriendsPrepared(Model):
    """Portable Friends study artifacts ready for the existing source and recall cores."""

    schema_version: Literal["storymodel.friends-prepared/v1"] = "storymodel.friends-prepared/v1"
    storyboard: FriendsStoryboard
    study: FriendsStudy
    source_request: SourceRequest
    source_rows_sha256: Digest
    crosswalk: FriendsCrosswalk
    recalls: tuple[FriendsPreparedRecall, ...]
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def receipt(self) -> Self:
        study = _validate_instance(FriendsStudy, self.study)
        if self.storyboard != study.storyboard or self.export_permission != study.export_permission:
            raise ValueError("Friends prepared receipt differs from declared study input")
        expected_source = _source_request(self.storyboard, self.export_permission)
        if self.source_request != expected_source:
            raise ValueError("Friends source request differs from declared storyboard")
        if self.source_rows_sha256 != _selected_rows_digest(self.storyboard):
            raise ValueError("Friends source provenance differs from selected storyboard rows")
        if self.crosswalk != _crosswalk(self.storyboard):
            raise ValueError("Friends crosswalk differs from declared presentation-time scales")
        expected_recalls = _prepared_recalls(study.recalls)
        if self.recalls != expected_recalls:
            raise ValueError("Friends recall artifacts differ from declared recall inputs")
        return self


def _utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def _selected_rows_digest(storyboard: FriendsStoryboard) -> str:
    """Bind source identity to all selected decoded columns, including both clock readings."""
    return digest(
        {
            "scale": storyboard.source_scale,
            "rows": [row.model_dump(mode="json") for row in storyboard.selected],
        }
    )


def _crosswalk(storyboard: FriendsStoryboard) -> FriendsCrosswalk:
    """Re-derive 56-to-52 links from shared presentation Time without arbitrary tie breaking."""
    entries: list[FriendsCrosswalkEntry] = []
    for source in storyboard.more_ems_56:
        candidates = tuple(
            FriendsCrosswalkCandidate(
                event=target.event,
                presentation_seconds=target.presentation_seconds,
                delta_seconds=abs(target.presentation_seconds - source.presentation_seconds),
            )
            for target in storyboard.narrcomb_52
            if abs(target.presentation_seconds - source.presentation_seconds)
            <= storyboard.crosswalk_tolerance_seconds
        )
        exact = tuple(candidate for candidate in candidates if candidate.delta_seconds == 0)
        nearest = min((candidate.delta_seconds for candidate in candidates), default=None)
        tied = (
            ()
            if nearest is None
            else tuple(candidate for candidate in candidates if candidate.delta_seconds == nearest)
        )
        if len(exact) == 1:
            kind: Literal["exact", "tolerance", "ambiguous", "unmatched"] = "exact"
            target = exact[0].event
        elif len(exact) > 1 or len(tied) > 1:
            kind, target = "ambiguous", None
        elif len(tied) == 1:
            kind, target = "tolerance", tied[0].event
        else:
            kind, target = "unmatched", None
        entries.append(
            FriendsCrosswalkEntry(
                more_ems_event=source.event,
                presentation_seconds=source.presentation_seconds,
                exact_candidates=exact,
                tolerance_candidates=candidates,
                target_event=target,
                kind=kind,
            )
        )
    return FriendsCrosswalk(
        tolerance_seconds=storyboard.crosswalk_tolerance_seconds,
        entries=tuple(entries),
    )


def _source_request(
    storyboard: FriendsStoryboard, export_permission: Literal["allowed", "denied"]
) -> SourceRequest:
    rows = storyboard.selected
    text = "\n".join(row.surface for row in rows)
    start = 0
    source_rows: list[SourceRow] = []
    for ordinal, row in enumerate(rows, 1):
        end = start + _utf16_length(row.surface)
        source_rows.append(
            SourceRow(
                key=f"{storyboard.source_scale}:{row.event}",
                span=Span(start=start, end=end),
            )
        )
        start = end + (1 if ordinal != len(rows) else 0)
    request = prepare_source(
        f"friends:{storyboard.source_scale}:{_selected_rows_digest(storyboard)}",
        text,
        source_rows,
        export_permission=export_permission,
    )
    return request


def _prepared_recalls(
    sheets: tuple[FriendsRecallSheet, ...] | tuple[FriendsPreparedRecall, ...],
) -> tuple[FriendsPreparedRecall, ...]:
    return tuple(
        FriendsPreparedRecall(
            participant=sheet.participant,
            recall=prepare_recall(
                f"friends-recall:{sheet.participant}",
                sheet.raw_text if isinstance(sheet, FriendsRecallSheet) else sheet.recall.raw_text,
            ),
            seconds_of_recall=sheet.seconds_of_recall,
        )
        for sheet in sheets
    )


def prepare_friends_study(value: FriendsStudy) -> FriendsPrepared:
    """Prepare declared source rows and recall units without creating time correspondences."""
    study = _validate_instance(FriendsStudy, value)
    return FriendsPrepared(
        storyboard=study.storyboard,
        study=study,
        source_request=_source_request(study.storyboard, study.export_permission),
        source_rows_sha256=_selected_rows_digest(study.storyboard),
        crosswalk=_crosswalk(study.storyboard),
        recalls=_prepared_recalls(study.recalls),
        export_permission=study.export_permission,
    )


def unresolved_source_proposal(value: FriendsPrepared) -> str:
    """Make a saved abstention artifact for synthetic plumbing checks, not semantic claims."""
    prepared = _validate_instance(FriendsPrepared, value)
    rows = []
    for row in prepared.source_request.rows:
        text = row.span.slice(prepared.source_request.document.text)
        rows.append(
            {
                "row": row.key,
                "status": "search-limited",
                "reason": "synthetic Friends fixture deliberately makes no narrative claim",
                "unresolved": [
                    UnresolvedClaim(
                        key="event",
                        kind="event",
                        reason="synthetic-token-control",
                        evidence=Quote(span=row.span, text=text),
                    ).model_dump(mode="json")
                ],
            }
        )
    return canonical({"schema_version": "storymodel.source-proposal/v1", "rows": rows})


def compile_friends_source(value: FriendsPrepared, proposal: str) -> SourceResult:
    """Compile an actual caller-supplied saved proposal after checking its declared input."""
    prepared = _validate_instance(FriendsPrepared, value)
    return compile_source(prepared.source_request, proposal)
