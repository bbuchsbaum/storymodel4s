"""Independent row/scene coordinates discriminate media composition from notebook repair."""

import json
from hashlib import sha256

import pytest
from test_sherlock_source import raw, repair

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.composition import inverse_support
from storymodel.playback import TickInterval
from storymodel.prepared_construction import prepare_sherlock_construction
from storymodel.sherlock_composition import (
    SherlockComposition,
    SherlockCompositionReadout,
    compose_sherlock_source,
    project_sherlock_composition,
    validate_sherlock_composition,
    verify_sherlock_composition_readout,
)
from storymodel.sherlock_source import prepare_sherlock_source


@pytest.fixture
def source():
    return prepare_sherlock_source("source", raw(), repair())


@pytest.fixture
def composed(source):
    return compose_sherlock_source(source, export_permission="allowed")


def bins():
    return tuple(
        TickInterval(start=str(a), end=str(b))
        for a, b in (
            (0, 75000),
            (75000, 76000),
            (76000, 106000),
        )
    )


def test_native_parts_compose_after_full_media_extent_not_last_annotation_or_notebook(composed):
    composition = composed.composition
    assert composition.seconds_per_tick == "1/2500"
    assert composition.end_tick == "106000"
    assert [(s.target.start, s.target.end) for s in composition.segments] == [
        ("0", "76000"),
        ("76000", "106000"),
    ]
    assert [(i.start, i.end) for i in composed.rows[4].support.intervals] == [("76000", "88500")]
    assert [(i.start, i.end) for i in composed.rows[4].anchors[0].native.intervals] == [
        ("0", "12500"),
    ]
    for row in composed.rows:
        assert inverse_support(composition, row.support) == row.anchors
    assert composed.source.raw_text.encode() == raw()
    assert composed.source.repair_text.encode() == repair()
    assert {s.sha256 for s in composition.sources} == {p.sha256 for p in composed.source.parts}


def test_readout_preserves_unannotated_tail_and_point_observation(composed):
    result = project_sherlock_composition(composed, bins=bins())
    assert result.playback.duration_seconds == "42/1"
    assert [c.duration_seconds for c in result.playback.cells] == ["30/1", "0/1", "12/1"]
    assert [c.coverage for c in result.playback.cells] == ["1/1", "0/1", "1/1"]
    assert result.playback.support.points == ("50000",)
    point = project_sherlock_composition(composed, ("sherlock:row:0003",), bins())
    assert point.playback.duration_seconds == "0/1"
    assert point.playback.support.intervals == ()
    assert point.playback.cells[0].points == ("50000",)
    combined = project_sherlock_composition(
        composed,
        ("sherlock:scene:001", "sherlock:row:0001"),
        bins(),
    )
    assert combined.playback.duration_seconds == "30/1"  # Union, not duplicated credit.


def test_scene_crossing_part_boundary_keeps_native_anchors_and_composed_gap():
    changed = raw().replace(b"2. B", b"")
    record = json.loads(repair())
    record["inputAnnotation"]["sha256"] = sha256(changed).hexdigest()
    intake = prepare_sherlock_source("source", changed, json.dumps(record).encode())
    composed = compose_sherlock_source(intake, export_permission="allowed")
    assert len(composed.scenes) == 1
    scene = composed.scenes[0]
    assert len(scene.anchors) == 6
    assert len({a.occurrence for a in scene.anchors}) == 2
    assert [(i.start, i.end) for i in scene.support.intervals] == [
        ("0", "75000"),
        ("76000", "106000"),
    ]
    assert project_sherlock_composition(composed, (scene.key,)).playback.duration_seconds == "42/1"


def test_consumer_recomputes_actual_intake_and_readout_not_copied_hash(composed):
    assert SherlockComposition.model_validate_json(composed.model_dump_json()) == composed
    row = composed.rows[4]
    forged_support = row.support.model_copy(
        update={"intervals": (TickInterval(start="0", end="12500"),)}
    )
    forged_row = row.model_copy(update={"support": forged_support})
    forged = composed.model_copy(
        update={"rows": (*composed.rows[:4], forged_row, composed.rows[5])}
    )
    with pytest.raises(ValueError, match="actual source intake"):
        validate_sherlock_composition(forged)
    with pytest.raises(ValueError):
        project_sherlock_composition(forged)
    result = project_sherlock_composition(composed, bins=bins())
    changed = result.model_copy(
        update={
            "playback": result.playback.model_copy(
                update={"duration_seconds": "424/10"},
            )
        }
    )
    with pytest.raises(ValueError, match="actual selected evidence"):
        verify_sherlock_composition_readout(composed, (), bins(), changed)
    assert verify_sherlock_composition_readout(composed, (), bins(), result) == result


def test_selection_and_permissions_refuse_before_export(source, composed, tmp_path):
    with pytest.raises(PermissionError):
        project_sherlock_composition(compose_sherlock_source(source))
    for units in (("unknown",), ("sherlock:row:0001", "sherlock:row:0001")):
        with pytest.raises(ValueError):
            project_sherlock_composition(composed, units)
    prepared = prepare_sherlock_construction(source)
    request = tmp_path / "denied.json"
    output = tmp_path / "absent.json"
    publish_json(request, prepared.model_dump(mode="json"))
    assert main(["sherlock-compose", str(request), "--output", str(output)]) == 2
    assert not output.exists()


def test_cli_composition_and_saved_readout_agree_with_api(source, composed, tmp_path):
    prepared = prepare_sherlock_construction(source, export_permission="allowed")
    request = tmp_path / "prepared.json"
    output = tmp_path / "composed.json"
    publish_json(request, prepared.model_dump(mode="json"))
    assert main(["sherlock-compose", str(request), "--output", str(output)]) == 0
    assert SherlockComposition.model_validate_json(output.read_bytes()) == composed
    assert main(["sherlock-compose", str(request), "--expected-input-sha256", "0" * 64]) == 2
    target_bins = tmp_path / "bins.json"
    publish_json(target_bins, [b.model_dump(mode="json") for b in bins()])
    result = tmp_path / "readout.json"
    args = [str(output), "--bins", str(target_bins), "--unit", "sherlock:row:0005"]
    assert main(["sherlock-composition-readout", *args, "--output", str(result)]) == 0
    readout = SherlockCompositionReadout.model_validate_json(result.read_bytes())
    assert readout == project_sherlock_composition(composed, ("sherlock:row:0005",), bins())
    assert main(["verify-sherlock-composition-readout", str(output), str(result), *args[1:]]) == 0
