"""Run recording exposure on replayed, synthetic Sherlock construction and mapping."""

import json

import pytest
from test_recall_clock import build_clock_example, query, two_unit_recording

from storymodel.analysis_workflow import replay_analysis_file
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.direct_mapping import RichMappingRequest, mapping_prompt, mapping_request_digest
from storymodel.direct_workflow import MappingRecording, import_mapping, replay_mapping
from storymodel.exposure import ExposureQuery, WordIntervals, recall_exposure
from storymodel.playback import TickInterval
from storymodel.serialization import canonical

artifacts = pytest.fixture(scope="module")(build_clock_example)


def window(a, b):
    return TickInterval(start=str(a), end=str(b))


def test_recorded_onsets_are_events_and_missing_counts_cover_the_entire_unit(artifacts):
    root = artifacts
    req = ExposureQuery.model_validate_json((root / "exposure-query.json").read_bytes())
    result = recall_exposure(root / "analysis-1.json", req)
    (row,) = result.units
    assert (row.duration_seconds, row.onset_only_ticks, row.missing_words) == ("3/4", ("110",), 0)
    assert result.uncovered_seconds == "13/4"
    assert result.seconds_without_complete_mapping == "0"
    assert row.decisions[0].status == "admitted" and row.mapping_coverage == "complete"
    plan = root / "analysis-2.json"
    req = ExposureQuery(
        **query(root, 2).model_dump(exclude={"schema_version", "bins", "intervals"}),
        mapping_stage="mapping",
        window=window(100, 110),
    )
    result = recall_exposure(plan, req)
    (row,) = result.units
    assert (row.duration_seconds, row.onset_only_ticks, row.missing_words) == ("0", (), 1)
    assert result.uncovered_seconds == "5/2"
    assert recall_exposure(plan, req.model_copy(update={"window": window(110, 111)})).units[
        0
    ].onset_only_ticks == ("110",)


def test_exposure_verifies_exact_artifact_and_rejects_foreign_or_inconsistent_inputs(
    artifacts, tmp_path
):
    root = artifacts
    plan = root / "analysis-1.json"
    req_path = root / "exposure-query.json"
    req = ExposureQuery.model_validate_json(req_path.read_bytes())
    emitted = tmp_path / "result.json"
    assert main(["recall-exposure", str(plan), str(req_path), "--output", str(emitted)]) == 0
    assert emitted.read_bytes() == (root / "exposure.json").read_bytes()
    args = ["verify-recall-exposure", str(plan), str(req_path), str(emitted)]
    assert main(args) == 0
    changed = json.loads(emitted.read_bytes())
    changed["units"][0]["duration_seconds"] = "0"
    emitted.write_text(json.dumps(changed))
    assert main(args) == 2

    def measured(intervals):
        return WordIntervals(recording_id=req.recording.id, provenance="test", by_word=intervals)

    for update, error in [
        ({"mapping_stage": "details"}, "mapping stage"),
        ({"intake_sha256": "0" * 64}, "another recall intake"),
        ({"word_intervals": measured({"other": window(105, 107)})}, "foreign word"),
        (
            {
                "word_intervals": measured(
                    {next(iter(req.word_intervals.by_word)): window(106, 107)}
                )
            },
            "recorded onset",
        ),
        ({"window": window(100, 121)}, "outside recording"),
        (
            {"recording": req.recording.model_copy(update={"key": "other", "id": ""})},
            "another recording",
        ),
    ]:
        with pytest.raises(ValueError, match=error):
            recall_exposure(plan, req.model_copy(update=update))


def test_distinct_units_share_measured_ticks_and_retain_mapping_history(
    artifacts, tmp_path, monkeypatch
):
    root, clock, units = two_unit_recording(artifacts, tmp_path, monkeypatch)
    plan_path = root / "multi.json"
    replay = replay_analysis_file(plan_path)
    account = replay.account
    plan = json.loads(plan_path.read_bytes())
    for name, unit, interpretation, failed in [
        ("first", units[0], "referent", False),
        ("other", units[1], "narrative", False),
        ("last", units[1], "referent", True),
    ]:
        request = RichMappingRequest(account=account, unit=unit.id, interpretation=interpretation)
        detail = {
            "detail": "d0",
            "reason": "synthetic processing",
            "status": "failed" if failed else "attempted",
        }
        if not failed:
            detail["outcome"] = "unresolved"
        raw = canonical(
            {
                "schema_version": "storymodel.mapping-proposal/v1",
                "packet_sha256": json.loads(mapping_prompt(request))["packet_sha256"],
                "details": [detail],
            }
        )
        publish_json(root / f"{name}.json", request.model_dump(mode="json"))
        import_mapping(
            request,
            MappingRecording(
                request_sha256=mapping_request_digest(request), origin="synthetic", reply=raw
            ),
            root / name,
        )
        account = replay_mapping(request, root / name).account
        plan["stages"].append(
            dict(kind="mapping", name=name, request=dict(path=f"{name}.json"), recording=name)
        )
    publish_json(root / "mapped.json", plan)
    words = replay.recall.request.intake.words
    req = ExposureQuery(
        **clock.model_dump(exclude={"schema_version", "bins", "intervals"}),
        window=window(104, 120),
        overlap="equal-share",
        mapping_stage="last",
        word_intervals=WordIntervals(
            recording_id=clock.recording.id,
            provenance="synthetic",
            by_word={
                words[0].id: window(105, 116),
                words[1].id: window(110, 114),
                words[2].id: window(113, 119),
            },
        ),
    )
    path = root / "mapped.json"
    result = recall_exposure(path, req)
    assert [s.name for s in result.mapping_stages] == ["first", "last"]
    assert result.interpretation == "referent"
    assert [r.inventory.unit for r in result.units] == [u.id for u in units]
    assert [r.duration_seconds for r in result.units] == ["19/8", "9/8"]
    assert [r.mapping_coverage for r in result.units] == ["complete", "incomplete"]
    assert [r.decisions[0].status for r in result.units] == ["admitted", "failed"]
    assert result.units[1].onset_only_ticks == ("118",)
    assert result.uncovered_seconds == "1/2" and result.seconds_without_complete_mapping == "9/8"
    early = recall_exposure(path, req.model_copy(update={"mapping_stage": "first"}))
    assert early.units[1].unrequested_details and not early.units[1].decisions
    with pytest.raises(ValueError, match="overlap"):
        recall_exposure(
            path, req.model_copy(update={"overlap": "refuse", "window": window(104, 105)})
        )


def test_word_measurement_matches_scala_exposure_oracle():
    from fractions import Fraction
    from pathlib import Path

    from storymodel.exposure import _measure_word_timing

    path = Path(__file__).parents[1] / "evidence/recall-exposure/expected.json"
    cases = json.loads(path.read_bytes())["cases"]
    assert len(cases) == 6
    for case in cases:
        units = case["input"]["units"]
        words, points, extents = {}, {}, {}
        for unit, data in units.items():
            observations = (
                [(Fraction(a, b), Fraction(c, d)) for a, b, c, d in data["intervals"]]
                + [(Fraction(*t), None) for t in data["onset_only"]]
                + [(None, None)] * data["missing_count"]
            )
            for i, (a, b) in enumerate(observations):
                word = f"{unit}/{i}"
                words[word] = unit
                points[word] = str(a) if a is not None else None
                if b is not None:
                    extents[word] = window(a, b)
        selection = window(*(Fraction(*t) for t in case["query"]))
        expected = case["expected"]
        if "refusal" in expected:
            assert expected["refusal"] == "OverlappingUnits"
            with pytest.raises(ValueError, match="overlap"):
                _measure_word_timing(words.items(), points, extents, selection, "refuse")
            continue
        timing, uncovered, unassigned = _measure_word_timing(
            words.items(), points, extents, selection, "equal-share"
        )
        assert not unassigned
        assert uncovered == Fraction(*expected["uncovered"]), case["id"]
        for unit, wanted in expected["units"].items():
            row = timing[unit]
            assert (row.ticks, tuple(row.onsets), row.missing) == (
                Fraction(*wanted["duration"]),
                tuple(str(Fraction(*at)) for at in wanted["onsets"]),
                wanted["missing_count"],
            ), case["id"]


def test_unassigned_intervals_leave_uncovered_time_and_repeated_onsets_count_once():
    from storymodel.exposure import _measure_word_timing

    rows, uncovered, unassigned = _measure_word_timing(
        (("outside", None), ("a", "unit"), ("b", "unit"), ("c", "unit")),
        {"outside": "0", "a": "2", "b": "2", "c": None},
        {"outside": window(0, 5)},
        window(0, 5),
        "refuse",
    )
    assert unassigned == ("outside",)
    assert (rows["unit"].ticks, tuple(rows["unit"].onsets), rows["unit"].missing) == (0, ("2",), 1)
    assert uncovered == 5
