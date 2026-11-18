"""Hand-calculated lexical coverage, timing and identity controls."""

import json
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.feature_windows import Lexicon, feature_windows
from storymodel.playback import TickInterval
from storymodel.recall_clock import RecordingQuery
from storymodel.serialization import canonical, digest


@pytest.fixture(scope="module")
def saved(tmp_path_factory):
    import subprocess

    root = tmp_path_factory.mktemp("windows") / "saved"
    subprocess.run(
        [
            sys.executable,
            str(Path(__file__).resolve().parents[1] / "examples/feature_windows.py"),
            str(root),
        ],
        check=True,
    )
    return root


def test_window_mean_and_missing_denominators_survive_replay(saved, tmp_path):
    for n, expected in (
        (1, [(2, 0, 1, 0), (2, None, 0, 1)]),
        (2, [(None, None, 0, 0), (2, None, 0, 1)]),
    ):
        args = [
            str(saved / f"analysis-{n}.json"),
            str(saved / f"timed-recall-{n}-query.json"),
            str(saved / "norms.json"),
        ]
        result = tmp_path / f"out-{n}.json"
        assert main(["feature-windows", *args, "--output", str(result)]) == 0
        assert result.read_bytes() == (saved / f"feature-windows-{n}.json").read_bytes()
        assert main(["verify-feature-windows", *args, str(result)]) == 0
        data = json.loads(result.read_bytes())
        words = [w["words"] for w in data["windows"]]
        assert [
            (
                w["token_length"]["mean"],
                w["lexicon"]["mean"],
                w["lexicon"]["measured"],
                w["lexicon"]["missing"],
            )
            for w in words
        ] == expected
        assert len(data["missing_onset"]) == n - 1
        assert not data["outside_windows"]
        assert [len(w["detail_ids"]) for w in data["windows"]] == [2 - n, 0]
        assert [s["lexicon"]["missing"] for s in data["source_scenes"].values()] == [7, 4]
        assert data["unit_intervals"]["1"]["lexicon"]["measured"] == 2 - n
        words[0]["lexicon"]["measured"] = 50
        result = tmp_path / f"changed-{n}.json"
        publish_json(result, data)
        assert main(["verify-feature-windows", *args, str(result)]) == 2


def test_lexicon_normalization_conflict_and_provenance():
    def norms(entries):
        return Lexicon(name="n", units="u", entries=entries)

    assert norms({" A ": 1.0, "b": 0}).id == norms({"B": 0, "a": 1}).id
    assert norms({"a": 1}).id != norms({"a": 2}).id
    for invalid in ({"a": float("nan")}, {"A": 1, "a": 2}, {" ": 0}):
        with pytest.raises(ValueError):
            norms(invalid)


def test_repeated_astral_words_have_distinct_spans_and_half_open_membership(
    saved, tmp_path, monkeypatch
):
    from storymodel.prepared_construction import (
        PreparedConstructionRecording,
        import_prepared_construction,
        prepare_sherlock_construction,
    )
    from storymodel.sherlock_recall import prepare_sherlock_recall

    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[1] / "examples"))
    import shutil

    from sherlock_workflow import recall_inputs

    root = tmp_path / "test"
    shutil.copytree(saved, root)
    raw, lineage = recall_inputs(1)
    raw = raw.replace(b"AA,", "𐐀A,".encode()).replace(b"BB,", "𐐀A,".encode())
    meta = json.loads(lineage)
    meta["sources"][0].update(localCsvSha256=sha256(raw).hexdigest(), localCsvByteLength=len(raw))
    intake = prepare_sherlock_recall(
        "unicode", raw, json.dumps(meta).encode(), source_id="synthetic-recall"
    )
    req = prepare_sherlock_construction(intake, export_permission="allowed")
    publish_json(root / "unicode.json", req.model_dump(mode="json"))
    import_prepared_construction(
        req,
        PreparedConstructionRecording(
            prepared_sha256=digest(req.model_dump(mode="json")),
            origin="synthetic",
            reply=canonical(
                {"schema_version": "storymodel.rich-construction-proposal/v1", "rows": []}
            ),
        ),
        root / "unicode-recording",
    )
    plan = json.loads((root / "analysis-1.json").read_bytes())
    plan["recall"]["file"] = {"path": "unicode.json"}
    plan["recall"]["recording"] = "unicode-recording"
    plan["stages"] = []
    publish_json(root / "plan.json", plan)
    q = RecordingQuery.model_validate_json(
        (root / "timed-recall-1-query.json").read_bytes()
    ).model_copy(
        update={
            "intake_sha256": digest(intake.model_dump(mode="json")),
            "bins": (TickInterval(start="104", end="105"), TickInterval(start="105", end="110")),
        }
    )
    r = feature_windows(root / "plan.json", q, Lexicon(name="n", units="u", entries={"𐐨a": 4}))
    a, b = r.recall_words
    assert a.occurrence != b.occurrence and a.length == b.length == 2
    assert (a.span.start, a.span.end, b.span.start, b.span.end) == (0, 3, 4, 7)
    assert [w.words.occurrences for w in r.windows] == [(), (a.occurrence,)]
    assert r.outside_windows == (b.occurrence,) and r.windows[1].words.lexicon.mean == 4
