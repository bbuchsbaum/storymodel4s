"""Hand-calculated lexical coverage, timing and identity controls."""

import json
import sys
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.feature_windows import Lexicon, feature_windows
from storymodel.model import Span
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
    assert norms({" A": -0.0, "a": 0.0}).id == norms({"a": -0.0}).id
    with pytest.raises(ValueError):
        Lexicon(name="a/b", units="u", entries={"a": 1.0})
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


@pytest.mark.parametrize(
    ("form", "eligible"),
    [("_", False), ("\u0301", False), ("23-4", False), ("٢,١", True), ("_1", True), ("X-1", True)],
)
def test_measure_eligibility_matches_surface_class_instead_of_token_start(form, eligible):
    from storymodel.feature_windows import _eligible

    assert _eligible(form) is eligible


def test_lexical_ledger_preserves_occurrences_and_matches_scala_normalization():
    from storymodel.feature_windows import LexicalLedger

    ledger = LexicalLedger(("Go", "go", "٢,١", "_", "23-4", "𐐀A"))

    assert [token.lexical for token in ledger.tokens] == [True, True, True, False, False, True]
    assert [token.normalized for token in ledger.tokens] == ["go", "go", "٢,١", None, None, "𐐨a"]
    assert [token.codepoint_length for token in ledger.tokens] == [2, 2, 3, 1, 4, 2]
    assert [ledger.type_frequency(i) for i in range(len(ledger.tokens))] == [2, 2, 1, None, None, 1]


def test_transcript_cannot_accidentally_be_counted_as_individual_characters():
    from storymodel.feature_windows import LexicalLedger

    with pytest.raises(TypeError, match="from_text"):
        LexicalLedger("AA AA")
    assert LexicalLedger.from_text("AA AA").type_frequency(0) == 2


def test_lexical_ledger_matches_scala_token_tracks_witness():
    from storymodel.feature_windows import LexicalLedger, _sample

    evidence = json.loads(
        (Path(__file__).resolve().parents[1] / "evidence/lexical-parity/results.json").read_bytes()
    )
    expected = evidence["result"]
    fixture = (
        (Path(__file__).resolve().parents[1] / "evidence/lexical-parity/cases.tsv")
        .read_text()
        .splitlines()
    )
    source = next(row.split("\t", 1)[1] for row in fixture if row.startswith("source\t"))
    entries = {
        fields[1]: float(fields[2])
        for row in fixture
        if (fields := row.split("\t"))[0] == "lexicon"
    }
    ledger = LexicalLedger.from_text(source)
    norms = Lexicon(name="probe", units="u", entries=entries)
    assert source == expected["source"]
    assert norms.entries == expected["lexicon_input"]
    assert [token.form for token in ledger.tokens] == expected["tokens"]

    actual = {"length": [], "frequency": [], "lexicon": []}
    for i, token in enumerate(ledger.tokens):
        if not token.lexical:
            for measure in actual.values():
                measure.append("ineligible")
            continue
        sample = _sample(str(i), Span(start=0, end=len(token.form)), token, None, norms)
        actual["length"].append(f"observed:{float(sample.length)}")
        actual["frequency"].append(f"observed:{float(ledger.type_frequency(i))}")
        actual["lexicon"].append(
            "missing:NotInLexicon"
            if sample.lexicon_score is None
            else f"observed:{sample.lexicon_score}"
        )
    for measure, values in actual.items():
        assert values == expected[measure], measure
