"""Independent span and segmentation witnesses for the narrow recall-intake profile."""

import json
from pathlib import Path

import pytest

from storymodel.recall_intake import (
    _token_spans,
    prepare_recall,
    prepare_recall_utf8,
    story_source_canonicalize,
    validate_prepared_recall,
)


def test_story_source_canonicalization_retains_raw_identity_and_normalizes_exactly() -> None:
    prepared = prepare_recall("control", "\nalpha  \r\nbeta\r\n\r\n\r\n\r\ngamma\n\n")
    assert prepared.canonical_text == "alpha\nbeta\n\ngamma"
    assert prepared.raw_sha256 != prepared.canonical_sha256
    assert prepared.document.text == prepared.canonical_text


def test_admitted_time_fixture_join_has_six_occurrences() -> None:
    fixture = json.loads((Path(__file__).parent / "fixtures/time-study-request.json").read_text())
    joined = "\n\n".join(unit["text"] for unit in fixture["recall"]["units"])
    prepared = prepare_recall(fixture["recall"]["key"], joined)
    assert [unit.text for unit in prepared.units] == [
        unit["text"] for unit in fixture["recall"]["units"]
    ]
    assert len(prepared.units) == 6


def test_sentence_clause_and_utf16_spans_are_hand_computed() -> None:
    prepared = prepare_recall("unicode-control", "😀 aa bb cc, and then dd ee ff. Dr. A zz.")
    actual = [(unit.u, unit.text, unit.support.start, unit.support.end) for unit in prepared.units]
    assert actual == [
        (1, "😀 aa bb cc", 0, 11),
        (2, "and then dd ee ff.", 13, 31),
        (3, "Dr. A zz.", 32, 41),
    ]
    assert prepared.units[0].support.slice(prepared.canonical_text) == prepared.units[0].text
    assert [(span.start, span.end) for span in prepared.separator_ranges] == [(11, 13), (31, 32)]


def test_surface_sentence_and_token_rules_keep_abbreviations_and_unicode_safe_tokens() -> None:
    prepared = prepare_recall("surface", "Dr. AA. BB?")
    assert [unit.text for unit in prepared.units] == ["Dr. AA.", "BB?"]
    text = "aa 3.5 x-y x's 😀"
    assert [text[start:end] for start, end in _token_spans(text, (0, len(text)))] == [
        "aa",
        "3.5",
        "x-y",
        "x's",
        "😀",
    ]


def test_java_whitespace_and_utf16_token_length_control_clause_boundaries() -> None:
    nbsp = prepare_recall("nbsp", "AA.\u00a0BB.")
    assert [unit.text for unit in nbsp.units] == ["AA.\u00a0BB."]
    emoji = prepare_recall("emoji", "😀 aa bb, and then cc dd ee.")
    assert [unit.text for unit in emoji.units] == [
        "😀 aa bb",
        "and then cc dd ee.",
    ]


def test_splitter_controls_differ_from_naive_punctuation_split() -> None:
    prepared = prepare_recall("controls", "aa bb cc so much dd ee ff. AA BB CC and then DD EE FF.")
    assert [unit.text for unit in prepared.units] == [
        "aa bb cc so much dd ee ff.",
        "AA BB CC",
        "and then DD EE FF.",
    ]
    assert len(prepared.units) == 3  # a naive punctuation splitter produces only two units


def test_short_clause_is_merged_and_forged_copy_is_refused() -> None:
    prepared = prepare_recall("short", "α β γ; δ ε.")
    assert [unit.text for unit in prepared.units] == ["α β γ; δ ε."]
    forged = prepared.model_copy(update={"canonical_sha256": "0" * 64})
    with pytest.raises(ValueError, match="deterministic intake"):
        validate_prepared_recall(forged)


def test_nonnarrative_unicode_controls_are_safe_and_no_sentence_is_silently_dropped() -> None:
    prepared = prepare_recall("controls", "𝔸-1 3.5. \u0085😀!")
    assert [unit.text for unit in prepared.units] == ["𝔸-1 3.5. \u0085😀!"]
    assert story_source_canonicalize("x \u0085") == "x\u0085"


def test_utf8_intake_is_strict_and_bom_preserving() -> None:
    prepared = prepare_recall_utf8("bom", b"\xef\xbb\xbf\xce\xb1 \xce\xb2 \xce\xb3.")
    assert prepared.raw_text.startswith("\ufeff")
    with pytest.raises(ValueError, match="invalid UTF-8"):
        prepare_recall_utf8("bad", b"\xff")


def test_java_uppercase_and_trim_controls_do_not_use_python_defaults() -> None:
    # Independently checked with Character.isUpperCase and String.trim on the JVM.
    prepared = prepare_recall("upper-control", "aa. Ⅰ bb.")
    assert [u.text for u in prepared.units] == ["aa.", "Ⅰ bb."]
    for character in ("\u0085", "\u00a0"):
        unit = prepare_recall("trim-control", character).units[0]
        assert unit.text == character and unit.support.start == 0 and unit.support.end == 1
