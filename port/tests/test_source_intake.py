"""Independent clock construction from the Scala TimeStudyFixture's native numeric inputs."""

import json
from pathlib import Path

import pytest

from storymodel.source_intake import NativeSource, compile_source, native_source_digest
from storymodel.study_workflow import read_request

FIXTURES = Path(__file__).parent / "fixtures"


def native():
    return NativeSource.model_validate_json((FIXTURES / "native-time-source.json").read_bytes())


def test_native_clock_construction_matches_existing_scala_parity_source():
    source = compile_source(native())
    expected = read_request(FIXTURES / "time-study-request.json").source
    assert source == expected
    # Independent arithmetic: 1*(300/10 - 10)+.5 = 20.5; .5*(700/10 - 60) = 5.
    assert source.clock.films[0].scenes[0].end == "41/2"
    assert source.clock.films[1].scenes[0].end == "5/1"
    assert source.clock.films[0].scenes[0].start == "0/1"


@pytest.mark.parametrize(
    "edit, message",
    [
        (lambda x: x["clips"][0].update(origin_seconds="0"), "first target row"),
        (lambda x: x["clips"][0].update(origin_seconds="10.5"), "first target row"),
        (lambda x: x["clips"][0].update(rate="0"), "positive"),
        (lambda x: x["clips"][0].update(duration_seconds="20.5"), "increase"),
        (lambda x: x.update(tolerance_seconds="-1"), "negative"),
        (lambda x: x.update(clips=x["clips"][:1]), "exactly"),
        (lambda x: x["rows"][2].update(target=False), "non-target scene"),
        (lambda x: x["rows"][0].update(part="missing"), "undeclared"),
        (lambda x: x["rows"][0].update(end_tick="1001"), "outside"),
        (lambda x: x["rows"][0].update(end_tick="-1"), "precedes"),
        (lambda x: x["rows"][0].update(number=2), "duplicate native row"),
        (lambda x: x["rows"][2].update(scene=2, collection=3), "multiple collections"),
        (lambda x: x["clips"][0].update(offset_seconds="-25"), "zero-anchored"),
    ],
)
def test_native_clock_refuses_incompatible_source_or_transform(edit, message):
    wire = native().model_dump(mode="json")
    edit(wire)
    with pytest.raises(ValueError, match=message):
        NativeSource.model_validate_json(json.dumps(wire))


def test_source_boundaries_revalidate_copies_and_derive_target_membership():
    value = native()
    with pytest.raises(ValueError, match="undeclared"):
        compile_source(
            value.model_copy(
                update={"parts": (value.parts[0].model_copy(update={"key": "foreign"}),)}
            )
        )
    source = compile_source(value)
    assert [(c.number, c.target) for c in source.collections] == [(1, True), (2, False), (3, True)]
    assert [(s.number, s.target) for s in source.scenes] == [
        (1, False),
        (2, True),
        (3, True),
        (4, False),
        (5, True),
        (6, True),
    ]


def test_scene_minimum_not_input_order_determines_boundary():
    value = native()
    extra = value.rows[1].model_copy(update={"number": 7, "start_tick": "150", "end_tick": "200"})
    reordered = value.model_copy(update={"rows": (extra,) + tuple(reversed(value.rows))})
    assert compile_source(reordered) == compile_source(value)
    assert native_source_digest(reordered) != native_source_digest(value)


def test_large_tick_inputs_do_not_round_before_clock_conversion():
    base = 2**54 + 1
    wire = {
        "key": "numeric-control",
        "parts": [
            {
                "key": "a",
                "start_tick": str(base),
                "end_tick": str(base + 100),
                "seconds_per_tick": "1",
            }
        ],
        "rows": [
            {
                "number": i + 1,
                "scene": i + 1,
                "collection": 1,
                "part": "a",
                "start_tick": str(base + i),
                "target": True,
            }
            for i in range(2)
        ],
        "clips": [
            {
                "collection": 1,
                "origin_seconds": str(base),
                "rate": "0.1",
                "offset_seconds": "0",
                "duration_seconds": "1",
            }
        ],
        "tolerance_seconds": "0",
    }
    source = compile_source(NativeSource.model_validate_json(json.dumps(wire)))
    assert source.clock.films[0].scenes[0].end == "1/10"
    wire["rows"][0]["start_tick"] = base
    with pytest.raises(ValueError):
        NativeSource.model_validate_json(json.dumps(wire))
