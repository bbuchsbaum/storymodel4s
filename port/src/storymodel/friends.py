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
from .model import Model, Name, Span, _validate_instance
from .recall_intake import PreparedRecall, prepare_recall
from .serialization import canonical
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


class FriendsPrepared(Model):
    """Portable Friends study artifacts ready for the existing source and recall cores."""

    schema_version: Literal["storymodel.friends-prepared/v1"] = "storymodel.friends-prepared/v1"
    storyboard: FriendsStoryboard
    source_request: SourceRequest
    recalls: tuple[FriendsPreparedRecall, ...]
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def receipt(self) -> Self:
        expected_source = _source_request(self.storyboard, self.export_permission)
        if self.source_request != expected_source:
            raise ValueError("Friends source request differs from declared storyboard")
        expected_recalls = _prepared_recalls(self.recalls)
        if self.recalls != expected_recalls:
            raise ValueError("Friends recall artifacts differ from declared recall inputs")
        return self


def _utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


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
        f"friends:{storyboard.source_scale}", text, source_rows, export_permission=export_permission
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
        source_request=_source_request(study.storyboard, study.export_permission),
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
