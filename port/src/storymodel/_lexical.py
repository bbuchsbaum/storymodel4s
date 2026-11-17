"""Generic English lexical normalization shared by recall-oriented workflows.

This is a bounded behavioral port of ``storymodel4s.recall.Lexical``.  It keeps
tokenization and stemming story-agnostic: callers provide any domain vocabulary.
"""

from __future__ import annotations

import unicodedata

STOPWORDS = frozenset(
    {
        "the",
        "a",
        "an",
        "and",
        "or",
        "but",
        "so",
        "then",
        "of",
        "to",
        "in",
        "on",
        "at",
        "into",
        "this",
        "that",
        "these",
        "those",
        "it",
        "its",
        "is",
        "was",
        "were",
        "be",
        "been",
        "are",
        "am",
        "he",
        "she",
        "they",
        "them",
        "his",
        "her",
        "their",
        "i",
        "we",
        "you",
        "me",
        "us",
        "my",
        "our",
        "your",
        "him",
        "there",
        "here",
        "kind",
        "sort",
        "some",
        "like",
        "with",
        "from",
        "for",
        "as",
        "by",
        "up",
        "down",
        "out",
        "about",
        "before",
        "after",
        "while",
        "because",
        "had",
        "has",
        "have",
        "did",
        "do",
        "does",
        "think",
        "guess",
        "maybe",
        "very",
        "really",
        "just",
        "not",
        "no",
        "somebody",
        "someone",
        "something",
        "anyone",
        "anything",
        "who",
        "what",
        "which",
        "when",
        "now",
        "one",
        "all",
        "if",
        "than",
    }
)

_IRREGULAR = {
    "went": "go",
    "gone": "go",
    "goes": "go",
    "came": "come",
    "saw": "see",
    "seen": "see",
    "heard": "hear",
    "said": "say",
    "told": "tell",
    "thought": "think",
    "hid": "hide",
    "hidden": "hide",
    "shot": "shoot",
    "felt": "feel",
    "fought": "fight",
    "began": "begin",
    "begun": "begin",
    "made": "make",
    "fell": "fall",
    "fallen": "fall",
    "was": "be",
    "were": "be",
    "been": "be",
    "is": "be",
    "are": "be",
    "am": "be",
    "got": "get",
    "gotten": "get",
    "spoke": "speak",
    "spoken": "speak",
    "left": "leave",
    "took": "take",
    "taken": "take",
    "gave": "give",
    "given": "give",
    "ran": "run",
    "struck": "strike",
    "found": "find",
    "brought": "bring",
    "bought": "buy",
    "caught": "catch",
    "taught": "teach",
    "sat": "sit",
    "stood": "stand",
    "knew": "know",
    "known": "know",
    "grew": "grow",
    "grown": "grow",
    "threw": "throw",
    "thrown": "throw",
    "drew": "draw",
    "drawn": "draw",
    "flew": "fly",
    "flown": "fly",
    "ate": "eat",
    "eaten": "eat",
    "drank": "drink",
    "drunk": "drink",
    "sang": "sing",
    "sung": "sing",
    "rang": "ring",
    "rung": "ring",
    "swam": "swim",
    "swum": "swim",
    "wrote": "write",
    "written": "write",
    "rode": "ride",
    "ridden": "ride",
    "drove": "drive",
    "driven": "drive",
    "woke": "wake",
    "woken": "wake",
    "broke": "break",
    "broken": "break",
    "chose": "choose",
    "chosen": "choose",
    "froze": "freeze",
    "frozen": "freeze",
    "forgot": "forget",
    "forgotten": "forget",
    "held": "hold",
    "kept": "keep",
    "slept": "sleep",
    "met": "meet",
    "led": "lead",
    "fed": "feed",
    "lost": "lose",
    "sent": "send",
    "spent": "spend",
    "built": "build",
    "lit": "light",
    "bit": "bite",
    "bitten": "bite",
    "hit": "hit",
    "put": "put",
    "cut": "cut",
    "let": "let",
    "set": "set",
    "shut": "shut",
    "hurt": "hurt",
    "read": "read",
    "did": "do",
    "done": "do",
    "had": "have",
    "has": "have",
    "died": "die",
    "dying": "die",
    "dies": "die",
    "lay": "lie",
    "lain": "lie",
    "paid": "pay",
    "laid": "lay",
    "meant": "mean",
    "wore": "wear",
    "worn": "wear",
    "tore": "tear",
    "torn": "tear",
    "burst": "burst",
    "men": "man",
    "women": "woman",
    "children": "child",
    "people": "people",
    "feet": "foot",
    "teeth": "tooth",
    "mice": "mouse",
    "geese": "goose",
}


def _simple_lower_char(character: str) -> str:
    """Use the first scalar of Python's full lowercase mapping as its simple mapping."""
    return character.lower()[0]


def lower(text: str) -> str:
    """Lowercase per Unicode code point without Python's length-changing full mapping."""
    return "".join(_simple_lower_char(character) for character in text)


def _is_letter_or_digit(character: str) -> bool:
    category = unicodedata.category(character)
    return category.startswith("L") or category == "Nd"


def words(text: str) -> tuple[str, ...]:
    """Return lowercased letter/digit/apostrophe tokens with trailing possessives removed."""
    result: list[str] = []
    token: list[str] = []

    def flush() -> None:
        if not token:
            return
        word = "".join(token)
        token.clear()
        stripped = word[:-2] if word.endswith("'s") else word.removesuffix("'")
        if stripped:
            result.append(stripped)

    for character in text:
        if _is_letter_or_digit(character) or character == "'":
            token.append(_simple_lower_char(character))
        else:
            flush()
    flush()
    return tuple(result)


def _is_consonant(word: str, index: int) -> bool:
    inverted = False
    while index > 0 and word[index] == "y":
        inverted = not inverted
        index -= 1
    return (word[index] not in "aeiou") != inverted


def _measure(word: str) -> int:
    previous_consonant = True
    result = 0
    for index, character in enumerate(word):
        consonant = (
            index == 0 or not previous_consonant if character == "y" else character not in "aeiou"
        )
        if consonant and not previous_consonant:
            result += 1
        previous_consonant = consonant
    return result


def _contains_vowel(word: str) -> bool:
    return any(not _is_consonant(word, index) for index in range(len(word)))


def _ends_double_consonant(word: str) -> bool:
    return len(word) >= 2 and word[-1] == word[-2] and _is_consonant(word, len(word) - 1)


def _ends_cvc(word: str) -> bool:
    length = len(word)
    return (
        length >= 3
        and _is_consonant(word, length - 1)
        and not _is_consonant(word, length - 2)
        and _is_consonant(word, length - 3)
        and word[-1] not in "wxy"
    )


def _replace_suffix(word: str, replacements: tuple[tuple[str, str], ...], minimum: int) -> str:
    for suffix, replacement in replacements:
        if word.endswith(suffix):
            stemmed = word.removesuffix(suffix)
            return stemmed + replacement if _measure(stemmed) > minimum else word
    return word


def _porter(input_word: str) -> str:
    if len(input_word) <= 2 or not input_word.isascii() or not input_word.isalpha():
        return input_word
    word = input_word
    if word.endswith("sses"):
        word = word[:-2]
    elif word.endswith("ies"):
        word = word[:-2]
    elif not word.endswith("ss") and word.endswith("s"):
        word = word[:-1]

    step_1b_extra = False
    if word.endswith("eed"):
        if _measure(word[:-3]) > 0:
            word = word[:-1]
    elif word.endswith("ed") and _contains_vowel(word[:-2]):
        word = word[:-2]
        step_1b_extra = True
    elif word.endswith("ing") and _contains_vowel(word[:-3]):
        word = word[:-3]
        step_1b_extra = True
    if step_1b_extra:
        if word.endswith(("at", "bl", "iz")):
            word += "e"
        elif _ends_double_consonant(word) and word[-1] not in "lsz":
            word = word[:-1]
        elif _measure(word) == 1 and _ends_cvc(word):
            word += "e"
    if word.endswith("y") and _contains_vowel(word[:-1]):
        word = word[:-1] + "i"

    word = _replace_suffix(
        word,
        (
            ("ational", "ate"),
            ("tional", "tion"),
            ("enci", "ence"),
            ("anci", "ance"),
            ("izer", "ize"),
            ("abli", "able"),
            ("alli", "al"),
            ("entli", "ent"),
            ("eli", "e"),
            ("ousli", "ous"),
            ("ization", "ize"),
            ("ation", "ate"),
            ("ator", "ate"),
            ("alism", "al"),
            ("iveness", "ive"),
            ("fulness", "ful"),
            ("ousness", "ous"),
            ("aliti", "al"),
            ("iviti", "ive"),
            ("biliti", "ble"),
        ),
        0,
    )
    word = _replace_suffix(
        word,
        (
            ("icate", "ic"),
            ("ative", ""),
            ("alize", "al"),
            ("iciti", "ic"),
            ("ical", "ic"),
            ("ful", ""),
            ("ness", ""),
        ),
        0,
    )
    for suffix in (
        "al",
        "ance",
        "ence",
        "er",
        "ic",
        "able",
        "ible",
        "ant",
        "ement",
        "ment",
        "ent",
        "ion",
        "ou",
        "ism",
        "ate",
        "iti",
        "ous",
        "ive",
        "ize",
    ):
        if word.endswith(suffix):
            stemmed = word.removesuffix(suffix)
            if _measure(stemmed) > 1 and (suffix != "ion" or stemmed.endswith(("s", "t"))):
                word = stemmed
            break
    if word.endswith("e"):
        stemmed = word[:-1]
        if _measure(stemmed) > 1 or (_measure(stemmed) == 1 and not _ends_cvc(stemmed)):
            word = stemmed
    if _measure(word) > 1 and _ends_double_consonant(word) and word.endswith("l"):
        word = word[:-1]
    return word


def stem(word: str) -> str:
    """Return an irregular dictionary form or the project’s Porter-derived English stem."""
    normalized = lower(word)
    stemmed = _porter(_IRREGULAR.get(normalized, normalized))
    return stemmed[:-1] + "i" if len(stemmed) > 1 and stemmed.endswith("y") else stemmed


def stems(text: str) -> tuple[str, ...]:
    """Return content stems after tokenization and stopword removal."""
    return tuple(stem(word) for word in words(text) if word not in STOPWORDS)


def stem_set(text: str) -> frozenset[str]:
    """Return the distinct content stems of text."""
    return frozenset(stems(text))
