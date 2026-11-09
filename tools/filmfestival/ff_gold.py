"""FilmFestival reference labels and the scene dictionary, as the transfer protocol defines them.

Protocol: docs/plans/2026-11-09-filmfestival-transfer-protocol.md.

The unit is the original authors' utterance. Each utterance is put in exactly one class:

- single           one specific scene of one of the ten films
- multi_contig     several scenes forming an unbroken range
- multi_noncontig  several scenes with a gap
- film_noscene     a film is named but no scene (the scheme's film-only and scene -1 codes)
- nonfilm          memory search, gave up, or off task
- cartoon          the lobby cartoon, out of scope

Scene ids are global (1-216). A scene whose every annotation row is a title card is not a
valid scene answer: the coding scheme excludes title scenes and codes a title mention as
film-only.
"""

import csv

EXPECTED_ROWS = 3226
FILMS = range(1, 11)
CARTOON = 11
TITLE_MARK = "fades in on a black screen"
SCENE_CLASSES = ("single", "multi_contig", "multi_noncontig")


class GoldError(ValueError):
    pass


def read_tsv(path):
    """Rows of a TSV whose text cells may contain quote characters.

    The default csv dialect treats a quote as a field delimiter and silently merges rows, so
    quoting is switched off.
    """
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))


def classify(movie, scenes):
    """The class of one utterance from its movie code and its list of scene ids."""
    if movie == CARTOON:
        return "cartoon"
    if movie not in FILMS:
        return "nonfilm"
    if not scenes:
        return "film_noscene"
    if len(scenes) == 1:
        return "single"
    ordered = sorted(scenes)
    contiguous = ordered == list(range(ordered[0], ordered[-1] + 1))
    return "multi_contig" if contiguous else "multi_noncontig"


def load_gold(path, expected_rows=EXPECTED_ROWS):
    """Utterances as dicts: participant, session, row, film, scenes, cls. Recall text is dropped."""
    rows = read_tsv(path)
    if expected_rows is not None and len(rows) != expected_rows:
        raise GoldError(f"expected {expected_rows} utterances, read {len(rows)}")
    out = []
    for r in rows:
        movie = int(r["movie"])
        scenes = [int(s) for s in r["scenes"].split()]
        cls = classify(movie, scenes)
        out.append(
            {
                "participant": r["participant"],
                "session": r["run"],
                "row": int(r["gold_row"]),
                "film": movie if cls not in ("nonfilm", "cartoon") else None,
                "scenes": frozenset(scenes),
                "cls": cls,
            }
        )
    keys = [(u["participant"], u["session"], u["row"]) for u in out]
    if len(set(keys)) != len(keys):
        raise GoldError("an utterance key is repeated")
    return out


def load_dictionary(annotation_path):
    """Scene dictionary from an annotation table: scene -> film, and the valid scene targets.

    Returns (film_of, valid): `film_of` maps every global scene id to its film code (1-10, or 11
    for the cartoon); `valid` is the set of ten-film scenes that are not title-only.
    """
    rows = read_tsv(annotation_path)
    order, descriptions = [], {}
    for r in rows:
        key = (r["run"], r["film"])
        if key not in order:
            order.append(key)
        descriptions.setdefault(int(r["scene_number"]), []).append(
            (key, r["description"])
        )
    # Films are numbered 1-10 in presentation order; the two cartoon intros are film 11.
    code, next_film = {}, 1
    for key in order:
        if "cartoon" in key[1].lower():
            code[key] = CARTOON
        else:
            code[key] = next_film
            next_film += 1
    if next_film != 11:
        raise GoldError(f"expected ten films, found {next_film - 1}")
    film_of, valid = {}, set()
    for scene, entries in descriptions.items():
        films = {code[key] for key, _ in entries}
        if len(films) != 1:
            raise GoldError(f"scene {scene} spans films")
        film = films.pop()
        film_of[scene] = film
        title_only = all(TITLE_MARK in text for _, text in entries)
        if film != CARTOON and not title_only:
            valid.add(scene)
    return film_of, valid


def check_join(gold, film_of):
    """Every gold scene id must exist and belong to the film its utterance names."""
    for u in gold:
        for scene in u["scenes"]:
            if scene not in film_of:
                raise GoldError(f"gold scene {scene} is not in the dictionary")
            if u["cls"] in SCENE_CLASSES and film_of[scene] != u["film"]:
                raise GoldError(f"gold scene {scene} is not in film {u['film']}")


def population(gold):
    """Per-session counts the protocol fixes: all, scene-specific and film-labelled (ten films)."""
    table = {}
    for u in gold:
        row = table.setdefault(
            u["session"], {"all": 0, "scene_specific": 0, "film_labelled": 0}
        )
        row["all"] += 1
        if u["cls"] in SCENE_CLASSES:
            row["scene_specific"] += 1
        if u["cls"] in SCENE_CLASSES or u["cls"] == "film_noscene":
            row["film_labelled"] += 1
    return table
