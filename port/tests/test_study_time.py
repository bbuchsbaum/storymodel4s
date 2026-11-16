"""Focused contract checks for strict-chunked-time/v1."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from storymodel.study_time import StudySource, resolve_time_item


def source(clock: bool = True) -> StudySource:
    return StudySource.model_validate(
        {
            "collections": [
                {"collection": 1, "isTarget": True},
                {"collection": 2, "isTarget": False},
            ],
            "scenes": [
                {"scene": 1, "collection": 1, "isTarget": True},
                {"scene": 2, "collection": 1, "isTarget": True},
                {"scene": 3, "collection": 2, "isTarget": False},
            ],
            **(
                {
                    "clock": {
                        "toleranceSeconds": "1/2",
                        "films": [
                            {
                                "collection": 1,
                                "durationSeconds": "10/1",
                                "scenes": [
                                    {"scene": 1, "start": "0/1", "end": "5/1"},
                                    {"scene": 2, "start": "5/1", "end": "10/1"},
                                ],
                            }
                        ],
                    }
                }
                if clock
                else {}
            ),
        }
    )


def test_time_boundary_and_outside_keep_raw_values_and_exact_near_list() -> None:
    row = resolve_time_item(
        source(),
        {
            "referent_film": 1,
            "referent_time": "0:05",
            "narrative_film": 1,
            "narrative_time": "0:11",
            "conf": 0.8,
            "ignored": "not-an-extra",
        },
    )
    assert row == {
        "referent": {
            "stated": {
                "status": "answered",
                "kind": "scene",
                "scene": 2,
                "near": [1, 2],
                "film_stated": 1,
                "time": "0:05",
                "seconds": 5.0,
            },
            "judgement": {"kind": "scene", "scene": 2, "collection": 1},
        },
        "narrative": {
            "stated": {
                "status": "answered",
                "kind": "time_outside",
                "film": 1,
                "near": [],
                "film_stated": 1,
                "time": "0:11",
                "seconds": 11.0,
            },
            "judgement": {"kind": "time-outside", "collection": 1},
        },
        "extras": {"conf": 0.8},
    }


@pytest.mark.parametrize(
    ("item", "reason"),
    [
        ({"referent_film": 1.0, "referent_time": "0:01"}, "invalid-film"),
        (
            {
                "referent_film": True,
                "referent_time": "0:01",
                "narrative_film": 1,
                "narrative_time": "bad",
            },
            "invalid-film",
        ),
        (
            {
                "referent_film": None,
                "referent_time": "bad",
                "narrative_film": 1,
                "narrative_time": "0:01",
            },
            "invalid-time",
        ),
    ],
)
def test_malformed_reply_values_are_typed_failures(item: dict[str, object], reason: str) -> None:
    row = resolve_time_item(source(), item)
    assert row["referent"]["judgement"] == {"kind": "invalid", "reason": reason}


def test_missing_clock_and_missing_or_unparsable_chunk_are_distinct() -> None:
    item = {
        "referent_film": 1,
        "referent_time": "0:01",
        "narrative_film": 1,
        "narrative_time": "0:01",
    }
    assert resolve_time_item(source(False), item)["referent"]["judgement"] == {
        "kind": "invalid",
        "reason": "no-clip-clock",
    }
    assert resolve_time_item(source(), None)["referent"]["judgement"] == {"kind": "omitted"}
    assert resolve_time_item(source(), None, failure="unparsable-chunk")["narrative"][
        "judgement"
    ] == {"kind": "unparsable-chunk"}


def test_resolver_revalidates_a_public_source_instance() -> None:
    forged = source().model_copy(update={"collections": ()})
    with pytest.raises(ValidationError, match="scene names an unknown collection"):
        resolve_time_item(forged, None)


@pytest.mark.parametrize(
    "edit",
    [
        lambda value: value | {"collections": [{"collection": 1, "isTarget": 1}]},
        lambda value: value | {"clock": value["clock"] | {"toleranceSeconds": "0.5"}},
        lambda value: (
            value
            | {
                "clock": value["clock"]
                | {
                    "films": [
                        value["clock"]["films"][0]
                        | {
                            "scenes": [
                                {"scene": 1, "start": "0/1", "end": "4/1"},
                                {"scene": 2, "start": "5/1", "end": "10/1"},
                            ]
                        }
                    ]
                }
            }
        ),
    ],
)
def test_source_rejects_non_exact_or_noncontiguous_clock(edit: object) -> None:
    wire = source().model_dump(by_alias=True)
    with pytest.raises(ValidationError):
        StudySource.model_validate(edit(wire))  # type: ignore[operator]
