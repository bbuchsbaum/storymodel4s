"""Deterministic, segmentation-only recall intake.

This is deliberately a small intake profile, not a Python ``RecallGraph``: it records exact
text occurrence anchors and their construction recipe, but makes no proposition, entity, cue,
or discourse-function assertion.  Its ``recall-intake/v1`` identifiers are pilot namespaces,
not Scala ``StoryId`` identities.
"""

from __future__ import annotations

import hashlib
import re
import unicodedata
from typing import Self

from pydantic import Field, model_validator

from .model import Document, Model, Span, canonical

RECIPE = (
    "recall-intake/v1;canonicalization=story-source/v1;surface=surface-analyzer/v1;"
    "segmenter=recall-segmenter/v1;regex-boundary=ascii;unicode=" + unicodedata.unidata_version
)

_ABBREVIATIONS = frozenset(
    "mr mrs ms dr prof sr jr vs etc e.g i.e inc ltd fig vol pp eds cf approx dept gov sgt capt "
    "hon ave blvd jan feb apr jun jul aug sept oct nov".split()
)
_SENTENCE_STARTERS = frozenset(
    "the then he she it they we i you but and so after before when while now there this that these "
    "those his her my our their its in on at a an one later next suddenly meanwhile however yes no "
    "what who why how where if as for by with to of from some all each every nobody everyone "
    "someone "
    "nothing something".split()
)
_CLOSERS = frozenset("\"”’')]}" + "»")
_OPENERS = frozenset("([{«")
_BRACKET_CLOSERS = frozenset(")] }»".replace(" ", ""))
_SPLITTERS: tuple[tuple[re.Pattern[str], int | None, frozenset[str]], ...] = (
    (re.compile(r";\s+", re.ASCII), None, frozenset()),
    (re.compile(r",?\s+(and\s+then)\b", re.ASCII | re.IGNORECASE), 1, frozenset()),
    (re.compile(r",?\s+(then)\b", re.ASCII | re.IGNORECASE), 1, frozenset()),
    (re.compile(r",?\s+(but)\b", re.ASCII | re.IGNORECASE), 1, frozenset()),
    (
        re.compile(r",?\s+(so)\b", re.ASCII | re.IGNORECASE),
        1,
        frozenset(("much", "many", "far", "long")),
    ),
    (re.compile(r",?\s+(because)\b", re.ASCII | re.IGNORECASE), 1, frozenset()),
    (re.compile(r",?\s+(while)\b", re.ASCII | re.IGNORECASE), 1, frozenset()),
    (
        re.compile(r",?\s+(before\s+(?:that|this))\b", re.ASCII | re.IGNORECASE),
        1,
        frozenset(),
    ),
    (
        re.compile(r",?\s+(after\s+(?:that|this))\b", re.ASCII | re.IGNORECASE),
        1,
        frozenset(),
    ),
    (
        re.compile(r",?\s+(prior\s+to\s+that)\b", re.ASCII | re.IGNORECASE),
        1,
        frozenset(),
    ),
    (
        re.compile(
            r",?\s+and\s+(he|she|they|it|i|we|there|the|a|an|his|her|their|my|our|you)\b",
            re.ASCII | re.IGNORECASE,
        ),
        1,
        frozenset(),
    ),
)


def _utf8_sha256(text: str) -> str:
    try:
        return hashlib.sha256(text.encode("utf-8")).hexdigest()
    except UnicodeEncodeError as error:
        raise ValueError("text contains malformed Unicode") from error


def _trim_canonical_end(text: str, removable: str) -> str:
    end = len(text) - 1 if text.endswith(("\u0085", "\u2028", "\u2029")) else len(text)
    start = end
    while start > 0 and text[start - 1] in removable:
        start -= 1
    return text[:start] + text[end:]


def story_source_canonicalize(raw_text: str) -> str:
    """Apply ``StorySource.canonicalize`` exactly, preserving raw-text identity separately."""
    _utf8_sha256(raw_text)
    unix_lines = raw_text.replace("\r\n", "\n").replace("\r", "\n")
    stripped = "\n".join(_trim_canonical_end(line, " \t") for line in unix_lines.split("\n"))
    collapsed = re.sub(r"\n{3,}", "\n\n", stripped)
    return _trim_canonical_end(collapsed.lstrip("\n"), "\n")


def _utf16_length(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def _is_whitespace(character: str) -> bool:
    """Match ``Character.isWhitespace(char)`` rather than Python's broader ``isspace``."""
    if ord(character) > 0xFFFF:
        return False
    if "\u0009" <= character <= "\u000d" or "\u001c" <= character <= "\u001f":
        return True
    return unicodedata.category(character) in {"Zs", "Zl", "Zp"} and character not in {
        "\u00a0",
        "\u2007",
        "\u202f",
    }


def _java_char_letter(character: str) -> bool:
    return ord(character) <= 0xFFFF and unicodedata.category(character).startswith("L")


def _java_char_upper(character: str) -> bool:
    # Character.isUpperCase includes Other_Uppercase (e.g. Roman numeral I), not only Lu.
    return ord(character) <= 0xFFFF and character.isupper()


def _java_char_digit(character: str) -> bool:
    return ord(character) <= 0xFFFF and unicodedata.category(character) == "Nd"


def _word_code_point(character: str) -> bool:
    category = unicodedata.category(character)
    return category.startswith("L") or category in {"Nd", "Mn", "Mc"} or character == "_"


def _digit_code_point(character: str) -> bool:
    return unicodedata.category(character) == "Nd"


def _simple_lower(text: str) -> str:
    # Unicode simple lowercase, as TextNorm.lower: no context or multi-code-point expansion.
    # U+0130 is the sole full lowercase expansion in the supported Unicode database.
    return "".join("i" if c == "İ" else c.lower() for c in text)


def _next_word(text: str, start: int, end: int) -> str:
    while start < end and not _java_char_letter(text[start]):
        start += 1
    finish = start
    while finish < end and _java_char_letter(text[finish]):
        finish += 1
    return _simple_lower(text[start:finish])


def _word_following(text: str, offset: int) -> str:
    """Match ``RecallSegmenter.wordFollowing``: only whitespace is skipped before a word."""
    start = offset
    while start < len(text) and _is_whitespace(text[start]):
        start += 1
    end = start
    while end < len(text) and _java_char_letter(text[end]):
        end += 1
    return text[start:end].lower()


def _is_abbreviation(text: str, dot: int, end: int) -> bool:
    start = dot - 1
    while start >= 0 and (_java_char_letter(text[start]) or text[start] == "."):
        start -= 1
    raw = text[start + 1 : dot]
    word = _simple_lower(raw)
    if not word:
        return False
    if word in _ABBREVIATIONS:
        return True
    segments = raw.split(".")
    initials = bool(segments) and all(len(piece) == 1 and piece.isupper() for piece in segments)
    return initials and _next_word(text, dot + 1, end) not in _SENTENCE_STARTERS


def _starts_sentence(character: str) -> bool:
    return _java_char_upper(character) or _java_char_digit(character) or character in "\"“‘([«'"


def _paragraph_spans(text: str) -> tuple[tuple[int, int], ...]:
    result: list[tuple[int, int]] = []
    index, length = 0, len(text)
    while index < length:
        while index < length and _is_whitespace(text[index]):
            index += 1
        if index >= length:
            break
        start, end, done = index, index, False
        while not done and index < length:
            if text[index] == "\n":
                probe = index + 1
                while probe < length and text[probe] in " \t":
                    probe += 1
                if probe >= length or text[probe] == "\n":
                    done = True
                else:
                    index += 1
            else:
                index += 1
                if not _is_whitespace(text[index - 1]):
                    end = index
        result.append((start, end))
    return tuple(result)


def _sentence_spans(text: str, paragraph: tuple[int, int]) -> tuple[tuple[int, int], ...]:
    result: list[tuple[int, int]] = []
    index, end, start, open_brackets = paragraph[0], paragraph[1], -1, []
    while index < end:
        character = text[index]
        if start < 0 and not _is_whitespace(character):
            start = index
        if character in _OPENERS:
            open_brackets.append(index)
        elif character in _BRACKET_CLOSERS and open_brackets:
            open_brackets.pop()
        if character in ".!?":
            terminal_end = index
            while terminal_end + 1 < end and text[terminal_end + 1] in ".!?":
                terminal_end += 1
            abbreviation = (
                character == "." and terminal_end == index and _is_abbreviation(text, index, end)
            )
            close_end = terminal_end
            while close_end + 1 < end and text[close_end + 1] in _CLOSERS:
                close_end += 1
                if text[close_end] in _BRACKET_CLOSERS and open_brackets:
                    open_brackets.pop()
            next_index = close_end + 1
            while next_index < end and _is_whitespace(text[next_index]):
                next_index += 1
            inside_bracket = (
                bool(open_brackets)
                and index - open_brackets[-1] <= 300
                and _utf16_length(text[open_brackets[-1] : index]) <= 300
            )
            boundary = (
                not abbreviation
                and not inside_bracket
                and (
                    next_index >= end
                    or (next_index > close_end + 1 and _starts_sentence(text[next_index]))
                )
            )
            if boundary:
                result.append((start, close_end + 1))
                start = -1
            index = close_end + 1
        else:
            index += 1
    if start >= 0:
        sentence_end = end
        while sentence_end > start and _is_whitespace(text[sentence_end - 1]):
            sentence_end -= 1
        if sentence_end > start:
            result.append((start, sentence_end))
    return tuple(result)


def _token_spans(text: str, within: tuple[int, int]) -> tuple[tuple[int, int], ...]:
    result: list[tuple[int, int]] = []
    index, end = within
    while index < end:
        character = text[index]
        if _is_whitespace(character):
            index += 1
        elif _word_code_point(character):
            start = index
            while index < end:
                current = text[index]
                if _word_code_point(current):
                    index += 1
                elif index > start and index + 1 < end:
                    previous, following = text[index - 1], text[index + 1]
                    joins_word = current in "'’-" and _word_code_point(following)
                    joins_number = (
                        current in ".,"
                        and _digit_code_point(previous)
                        and _digit_code_point(following)
                    )
                    if joins_word or joins_number:
                        index += 1
                    else:
                        break
                else:
                    break
            result.append((start, index))
        else:
            result.append((index, index + 1))
            index += 1
    return tuple(result)


def _trim_clause(text: str, span: tuple[int, int]) -> tuple[int, int] | None:
    start, end = span
    while start < end and (_is_whitespace(text[start]) or text[start] in ",;"):
        start += 1
    while end > start and (_is_whitespace(text[end - 1]) or text[end - 1] in ",;"):
        end -= 1
    return (start, end) if end > start else None


def _split_clauses(text: str, sentence: tuple[int, int]) -> tuple[tuple[int, int], ...]:
    sentence_text = text[sentence[0] : sentence[1]]
    candidates: list[tuple[int, int, int, int]] = []
    for priority, (pattern, group, rejected) in enumerate(_SPLITTERS):
        for match in pattern.finditer(sentence_text):
            following = _word_following(sentence_text, match.end())
            if following not in rejected:
                clause_start = match.end() if group is None else match.start(group)
                candidates.append((match.start(), clause_start, match.end(), priority))
    candidates.sort(key=lambda candidate: (candidate[0], candidate[3]))
    boundaries: list[tuple[int, int, int, int]] = []
    for candidate in candidates:
        if not boundaries or candidate[0] >= boundaries[-1][2]:
            boundaries.append(candidate)
    pieces: list[tuple[int, int]] = []
    begin = 0
    for separator_start, clause_start, _occupied_end, _priority in boundaries:
        pieces.append((sentence[0] + begin, sentence[0] + separator_start))
        begin = clause_start
    pieces.append((sentence[0] + begin, sentence[1]))
    merged: list[tuple[int, int]] = []
    for piece in pieces:
        token_count = sum(
            _utf16_length(text[start:end]) > 1 for start, end in _token_spans(text, piece)
        )
        previous_short = (
            token_count >= 3
            and bool(merged)
            and sum(
                _utf16_length(text[start:end]) > 1 for start, end in _token_spans(text, merged[-1])
            )
            < 3
        )
        if merged and (token_count < 3 or previous_short):
            merged[-1] = (merged[-1][0], piece[1])
        else:
            merged.append(piece)
    return tuple(trimmed for piece in merged if (trimmed := _trim_clause(text, piece)) is not None)


class RecallOccurrence(Model):
    """One sequential recall occurrence with exact canonical UTF-16 support."""

    u: int = Field(ge=1)
    text: str = Field(min_length=1)
    support: Span


class PreparedRecall(Model):
    """Checked intake receipt; call ``validate_prepared_recall`` at public consumption."""

    id: str
    key: str = Field(min_length=1)
    raw_text: str
    raw_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    canonical_text: str
    canonical_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    recipe: str
    document: Document
    units: tuple[RecallOccurrence, ...] = Field(min_length=1)
    separator_ranges: tuple[Span, ...]

    @model_validator(mode="after")
    def receipt_is_self_consistent(self) -> Self:
        expected = _prepared_fields(self.key, self.raw_text)
        actual = self.model_dump(mode="json")
        if canonical(actual) != canonical(expected):
            raise ValueError("prepared recall differs from deterministic intake")
        return self


def _prepared_fields(key: str, raw_text: str) -> dict[str, object]:
    if not key:
        raise ValueError("recall key must not be blank")
    if not raw_text.strip("".join(chr(i) for i in range(33))):
        raise ValueError("recall text must not be blank")
    raw_sha256 = _utf8_sha256(raw_text)
    text = story_source_canonicalize(raw_text)
    canonical_sha256 = _utf8_sha256(text)
    document = Document.create(key, "recall", text)
    char_units = tuple(
        clause
        for paragraph in _paragraph_spans(text)
        for sentence in _sentence_spans(text, paragraph)
        for clause in _split_clauses(text, sentence)
    )
    # One linear coordinate map; repeated prefix encodes would be quadratic on long recall.
    offsets = [0]
    for character in text:
        offsets.append(offsets[-1] + (2 if ord(character) > 0xFFFF else 1))
    occurrences = tuple(
        RecallOccurrence(
            u=index + 1, text=text[start:end], support=Span(start=offsets[start], end=offsets[end])
        )
        for index, (start, end) in enumerate(char_units)
    )
    if not occurrences:
        raise ValueError("recall text yields no occurrence units")
    separators: list[Span] = []
    offset = 0
    for occurrence in occurrences:
        if offset < occurrence.support.start:
            separators.append(Span(start=offset, end=occurrence.support.start))
        offset = occurrence.support.end
    canonical_length = offsets[-1]
    if offset < canonical_length:
        separators.append(Span(start=offset, end=canonical_length))
    intake_id = (
        "recall-intake/v1:"
        + hashlib.sha256(
            canonical({"key": key, "raw_sha256": raw_sha256, "recipe": RECIPE}).encode("utf-8")
        ).hexdigest()
    )
    return {
        "id": intake_id,
        "key": key,
        "raw_text": raw_text,
        "raw_sha256": raw_sha256,
        "canonical_text": text,
        "canonical_sha256": canonical_sha256,
        "recipe": RECIPE,
        "document": document.model_dump(mode="json"),
        "units": tuple(occurrence.model_dump(mode="json") for occurrence in occurrences),
        "separator_ranges": tuple(span.model_dump(mode="json") for span in separators),
    }


def prepare_recall(key: str, raw_text: str) -> PreparedRecall:
    """Canonicalize and segment raw recall without making semantic recall-graph claims."""
    return PreparedRecall.model_validate(_prepared_fields(key, raw_text))


def prepare_recall_utf8(key: str, raw: bytes) -> PreparedRecall:
    """Strict UTF-8 intake, retaining a BOM when present as Scala ``fromUtf8`` does."""
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("invalid UTF-8") from error
    if text.encode("utf-8") != raw:
        raise ValueError("invalid UTF-8")
    return prepare_recall(key, text)


def validate_prepared_recall(value: PreparedRecall) -> PreparedRecall:
    """Recompute a public artifact, refusing forged ``model_copy`` or stale derived fields."""
    checked = PreparedRecall.model_validate(value)
    rebuilt = prepare_recall(checked.key, checked.raw_text)
    if canonical(rebuilt.model_dump(mode="json")) != canonical(checked.model_dump(mode="json")):
        raise ValueError("prepared recall differs from deterministic intake")
    return rebuilt
