"""Synthetic CSV controls for the retained strict word replay and external clocks."""

import csv
import io
import json
from hashlib import sha256

import pytest

from storymodel.sherlock_recall import (
    prepare_sherlock_recall,
    sherlock_recall_timing,
    validate_sherlock_recall,
    verify_sherlock_recall_timing,
)

HEADER = (
    "Words",
    "Princeton Dataspace Word Onset Time (sec)",
    "Princeton Dataspace TR Number",
    "Princeton Dataspace TR Onset (sec)",
    "OpenNeuro Word Onset Time (sec)",
    "OpenNeuro TR Number",
    "OpenNeuro TR Onset (sec)",
)


def csv_bytes(rows, *, ending="\r\n", bom=False):
    stream = io.StringIO(newline="")
    writer = csv.writer(stream, lineterminator=ending)
    writer.writerow(HEADER)
    writer.writerows(rows)
    return ("\ufeff" if bom else "").encode() + stream.getvalue().encode()


def lineage(raw, count):
    return json.dumps(
        {
            "schema": "storymodel4s.sherlock.recall-lineage",
            "schemaVersion": 1,
            "publicRelease": {"tag": "synthetic", "commit": "a" * 40},
            "sources": [
                {
                    "id": "synthetic-recall",
                    "localCsvByteLength": len(raw),
                    "localCsvSha256": sha256(raw).hexdigest(),
                    "recordCount": count,
                }
            ],
        }
    ).encode()


def prepare(rows, **options):
    raw = csv_bytes(rows, **options)
    return prepare_sherlock_recall(
        "recall", raw, lineage(raw, len(rows)), source_id="synthetic-recall"
    )


def row(word, princeton="1.25", openneuro="101.5"):
    return (word, princeton, "7", "1.0", openneuro, "70", "101.0")


def test_exact_csv_replay_keeps_bom_quoting_blank_rows_and_both_clocks():
    value = prepare([row(" AA,"), row("   ", "", "-1"), row("BB.", "2.5", "102.0")], bom=True)
    assert value.raw_text.startswith("\ufeff")
    assert value.prepared.raw_text == value.prepared.canonical_text == "AA, BB."
    assert [r.cells[0] for r in value.records] == [" AA,", "   ", "BB."]
    assert [
        None if r.joined_span is None else (r.joined_span.start, r.joined_span.end)
        for r in value.records
    ] == [(0, 3), None, (4, 7)]
    assert [(w.span.start, w.span.end, w.unit) for w in value.words] == [(0, 2, 1), (4, 6, 1)]
    p = sherlock_recall_timing(value, "princeton")
    o = sherlock_recall_timing(value, "openneuro")
    assert [r.onset_seconds for r in p.records] == ["5/4", None, "5/2"]
    assert [r.onset_seconds for r in o.records] == ["203/2", "-1/1", "102/1"]
    assert p.records[1].status == "excluded-blank-word" and p.records[1].word is None
    assert p.unit_onsets[0].onset_seconds == "5/4"
    assert o.unit_onsets[0].onset_seconds == "203/2"
    assert p.origin == p.recording_identity == "unestablished"
    assert p.recording_link == "not-established"
    assert verify_sherlock_recall_timing(value, p) == p


def test_byte_changes_keep_text_and_change_word_and_recording_lineage():
    rows = [row("AA"), row("BB.")]
    a = prepare(rows, ending="\r\n")
    b = prepare(rows, ending="\n")
    assert a.prepared == b.prepared
    assert a.raw_sha256 != b.raw_sha256
    assert [w.span for w in a.words] == [w.span for w in b.words]
    assert {w.id for w in a.words}.isdisjoint(w.id for w in b.words)


def test_initial_missing_onset_is_not_replaced_by_later_time_and_order_is_not_sorted():
    value = prepare([row("AA", ""), row("BB", "9"), row("CC.", "1")])
    timing = sherlock_recall_timing(value, "princeton")
    assert timing.unit_onsets[0].first_word == value.words[0].id
    assert timing.unit_onsets[0].onset_seconds is None
    assert [r.onset_seconds for r in timing.records] == [None, "9/1", "1/1"]


def test_repeated_words_and_unassigned_connectives_do_not_shift_unit_onset_binding():
    tokens = ("AA", "BB", "CC", "and", "he", "AA", "BB", "CC.")
    value = prepare([row(token, str(index)) for index, token in enumerate(tokens, 1)])
    assert [(unit.support.start, unit.support.end) for unit in value.prepared.units] == [
        (0, 8),
        (13, 25),
    ]
    assert [word.unit for word in value.words] == [1, 1, 1, None, 2, 2, 2, 2]
    assert value.words[0].id != value.words[5].id
    timing = sherlock_recall_timing(value, "princeton")
    assert [(unit.unit, unit.onset_seconds) for unit in timing.unit_onsets] == [
        (1, "1/1"),
        (2, "5/1"),
    ]
    assert timing.records[3].onset_seconds == "4/1"
    assert timing.records[3].word == value.words[3].id


@pytest.mark.parametrize(
    "token, expected",
    [
        ("-1.25", "-5/4"),
        ("+2.5e-2", "1/40"),
        ("1.", "1/1"),
        (".5", "1/2"),
        ("0e128", "0/1"),
        ("1e-19", "1/10000000000000000000"),
        ("9223372036854775808", "9223372036854775808/1"),
    ],
)
def test_selected_onsets_are_exact_bounded_decimals(token, expected):
    assert (
        sherlock_recall_timing(prepare([row("AA", token)]), "princeton").records[0].onset_seconds
        == expected
    )


@pytest.mark.parametrize("token", ["NaN", "Infinity", "1/2", ".", "1e129", "1" * 513])
def test_malformed_or_resource_limited_selected_clock_does_not_fabricate_a_time(token):
    value = prepare([row("AA", token)])  # Word replay does not interpret unused clock cells.
    with pytest.raises(ValueError):
        sherlock_recall_timing(value, "princeton")
    assert sherlock_recall_timing(value, "openneuro").records[0].onset_seconds == "203/2"


@pytest.mark.parametrize("word", ["AA BB", ",", "😀"])
def test_one_onset_is_never_silently_split_across_words_or_assigned_to_punctuation(word):
    value = prepare([row(word), row("ZZ")])
    with pytest.raises(ValueError, match="lexical word"):
        sherlock_recall_timing(value, "princeton")


def test_quoted_crlf_replay_needs_character_map_before_timing_not_silent_offset_reuse():
    value = prepare([row("AA\r\nBB")])
    assert value.records[0].cells[0] == "AA\r\nBB"
    assert value.prepared.canonical_text == "AA\nBB"
    with pytest.raises(ValueError, match="character map"):
        sherlock_recall_timing(value, "princeton")


@pytest.mark.parametrize(
    "bad",
    [b'AA"BB,1,2,3,4,5,6\n', b'"AA"x,1,2,3,4,5,6\n', b'"AA,1,2,3,4,5,6\n', b"\n", b"AA,1,2\n"],
)
def test_strict_csv_refuses_bad_quotes_and_physical_empty_records(bad):
    raw = (",".join(HEADER) + "\n").encode() + bad
    with pytest.raises(ValueError):
        prepare_sherlock_recall("recall", raw, lineage(raw, 1), source_id="synthetic-recall")


def test_wrong_byte_identity_header_count_and_duplicate_source_are_refused():
    raw = csv_bytes([row("AA")])
    original = lineage(raw, 1)
    with pytest.raises(ValueError, match="byte identity"):
        prepare_sherlock_recall("recall", raw + b"\n", original, source_id="synthetic-recall")
    with pytest.raises(ValueError, match="count"):
        prepare_sherlock_recall("recall", raw, lineage(raw, 2), source_id="synthetic-recall")
    bad = raw.replace(b"Words", b"words")
    with pytest.raises(ValueError, match="header"):
        prepare_sherlock_recall("recall", bad, lineage(bad, 1), source_id="synthetic-recall")
    duplicate = json.loads(original)
    duplicate["sources"] *= 2
    with pytest.raises(ValueError, match="exactly one"):
        prepare_sherlock_recall(
            "recall", raw, json.dumps(duplicate).encode(), source_id="synthetic-recall"
        )


def test_forged_parsed_records_and_timing_are_recomputed_from_actual_csv():
    first = prepare([row("AA", "1")])
    changed = prepare([row("AA", "2")])
    stale = changed.model_copy(update={"records": first.records})
    with pytest.raises(ValueError, match="exact CSV"):
        validate_sherlock_recall(stale)
    with pytest.raises(ValueError, match="exact intake"):
        verify_sherlock_recall_timing(changed, sherlock_recall_timing(first, "princeton"))
    forged = first.model_copy(update={"words": ()})
    with pytest.raises(ValueError):
        sherlock_recall_timing(forged, "princeton")
