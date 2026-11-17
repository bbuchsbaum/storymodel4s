"""Independent behavior controls for the bounded Scala Lexical port."""

import pytest

from storymodel._lexical import STOPWORDS, lower, stem, stem_set, stems, words


def test_scala_examples_keep_simple_lowering_and_token_boundaries():
    assert lower("İSTANBUL Ünïcode") == "istanbul ünïcode"
    assert lower("ABC") == "abc"
    assert words("Anna's house, 😀 cellar!") == ("anna", "house", "cellar")


def test_unicode_letters_and_decimal_digits_stay_in_words_but_symbols_split_them():
    assert words("ÉLÈVE १२३, 東京's—café") == ("élève", "१२३", "東京", "café")
    assert words("Ⅻ½_a") == ("a",)
    assert words("dogs' rock's") == ("dogs", "rock")


@pytest.mark.parametrize(
    ("input_word", "expected"),
    [
        ("caresses", "caress"),
        ("ponies", "poni"),
        ("ties", "ti"),
        ("caress", "caress"),
        ("cats", "cat"),
        ("feed", "feed"),
        ("agreed", "agre"),
        ("plastered", "plaster"),
        ("motoring", "motor"),
        ("conflated", "conflat"),
        ("troubled", "troubl"),
        ("relational", "relat"),
        ("triplicate", "triplic"),
        ("formalize", "formal"),
        ("communism", "commun"),
        ("probate", "probat"),
        ("controll", "control"),
    ],
)
def test_porter_literal_pairs(input_word: str, expected: str):
    assert stem(input_word) == expected


@pytest.mark.parametrize(
    "family",
    [
        ("walk", "walked", "walking", "walks"),
        ("cry", "cries", "cried", "crying"),
        ("house", "houses"),
        ("go", "goes", "went", "gone", "going"),
        ("find", "found", "finds", "finding"),
        ("box", "boxes"),
        ("scream", "screams", "screamed", "screaming"),
        ("paddle", "paddles", "paddled", "paddling"),
        ("shoot", "shot", "shoots", "shooting"),
        ("carry", "carried", "carries", "carrying"),
    ],
)
def test_scala_inflection_families_share_one_stem(family: tuple[str, ...]):
    assert len({stem(word) for word in family}) == 1


def test_stopword_filtering_and_set_deduplication():
    assert "the" in STOPWORDS
    assert stems("The people went with mice and went") == ("peopl", "go", "mous", "go")
    assert stem_set("The people went with mice and went") == frozenset({"peopl", "go", "mous"})


def test_final_y_normalization_unifies_otherwise_divergent_porter_forms():
    assert stem("cry") == stem("cries") == "cri"
    assert stem("sky") == "ski"


def test_story_agnostic_words_are_not_synonym_collapsed():
    assert stem("boat") != stem("canoe")
    assert stem("misty") != stem("fog")
    assert stem("back") != stem("return")


def test_long_y_runs_do_not_require_python_recursion():
    assert stem("y" * 2000) == "y" * 1999 + "i"
