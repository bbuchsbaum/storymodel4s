#!/usr/bin/env python3
"""Post hoc baselines and error structure for the FilmFestival transfer result.

These were not prespecified. They were added after the scores were seen, on an independent
reviewer's suggestion, so that scene agreement is not read against uniform chance alone. Every
baseline is scored like the mapper: one selected scene, a hit when it is in the reference set,
over the scene-specific utterances of the ten films.

Usage: ff_baselines.py GOLD CODES ANNOTATION REPLIES_DIR OUTPUT

Baselines:
- modal scene per film, film given: the most-used reference scene of the utterance's film
  (chosen on the same data, so it is an upper bound for a one-scene-per-film rule);
- lexical: word overlap between the utterance alone and each valid scene's concatenated
  descriptions, best of all 203 scenes (Jaccard, and TF-IDF cosine), and TF-IDF with the film
  given;
- copy-forward oracle: the highest scene of the previous utterance's reference;
- order oracle: that scene or the one after it.
The two oracles read the previous utterance's reference label, which no mapper sees.

Error structure of REPLIES_DIR's referent answers: whether a wrong scene is in the right film
and how many scenes away it is, and agreement within one scene.
"""

import collections
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402
import ff_score  # noqa: E402

SCHEMA = "storymodel4s.filmfestival.transfer-baselines/v1"
STOP = set(
    "a an the and or but of to in on at for with from by as is are was were be been being it its "
    "this that these those he she they them his her their him i you we me my our your so then "
    "there here like just um uh yeah okay oh well really kind sort think know remember guess "
    "don't not no do does did have has had would could can will got get gets going goes go went "
    "one two some what which who when where how up down out into over about also very".split()
)


def tokens(text):
    return [
        w for w in re.findall(r"[a-z']+", text.lower()) if w not in STOP and len(w) > 1
    ]


def scene_documents(annotation_path, valid):
    docs = collections.defaultdict(list)
    for r in ff_gold.read_tsv(annotation_path):
        docs[int(r["scene_number"])].append(r["description"])
    return {s: collections.Counter(tokens(" ".join(docs[s]))) for s in sorted(valid)}


def tfidf(documents):
    df = collections.Counter(w for doc in documents.values() for w in doc)
    idf = {w: math.log((1 + len(documents)) / (1 + c)) + 1 for w, c in df.items()}
    vectors = {}
    for s, doc in documents.items():
        v = {w: (1 + math.log(c)) * idf[w] for w, c in doc.items()}
        norm = math.sqrt(sum(x * x for x in v.values()))
        vectors[s] = {w: x / norm for w, x in v.items()}
    return idf, vectors


def best(scores):
    """The first scene with the highest positive score, in scene order; None if none is positive."""
    top, chosen = 0.0, None
    for s, value in scores:
        if value > top:
            top, chosen = value, s
    return chosen


def main(argv):
    if len(argv) != 5 or os.path.exists(argv[4]):
        print(__doc__, file=sys.stderr)
        return 2
    gold_path, codes_path, annotation_path, replies_dir, output = argv
    gold = ff_gold.load_gold(gold_path, codes_path)
    film_of, valid = ff_gold.load_dictionary(annotation_path)
    ff_gold.check_join(gold, film_of)
    text = {
        (r["participant"], r["run"], int(r["gold_row"])): r["text"]
        for r in ff_gold.read_tsv(gold_path)
    }
    documents = scene_documents(annotation_path, valid)
    idf, vectors = tfidf(documents)
    predicted = ff_score.load_predictions(replies_dir, "referent", gold)

    def cosine(key, candidates):
        counts = collections.Counter(w for w in tokens(text[key]) if w in idf)
        query = {w: (1 + math.log(c)) * idf[w] for w, c in counts.items()}
        return best(
            (s, sum(x * vectors[s].get(w, 0.0) for w, x in query.items()))
            for s in candidates
        )

    def jaccard(key, candidates):
        query = set(tokens(text[key]))
        return best(
            (s, len(query & set(documents[s])) / len(query | set(documents[s])))
            for s in candidates
            if query | set(documents[s])
        )

    report = {
        "schema": SCHEMA,
        "post_hoc": True,
        "valid_scene_targets": len(valid),
        "sessions": {},
    }
    ordered = sorted(valid)
    for session in sorted({u["session"] for u in gold}):
        own = sorted(
            (u for u in gold if u["session"] == session),
            key=lambda u: (u["participant"], u["row"]),
        )
        previous = {}
        for a, b in zip(own, own[1:]):
            if a["participant"] == b["participant"]:
                previous[(b["participant"], b["row"])] = a
        scored = [u for u in own if u["cls"] in ff_gold.SCENE_CLASSES]
        modal = {}
        for film in ff_gold.FILMS:
            counts = collections.Counter(
                s
                for u in scored
                if u["film"] == film
                for s in u["scenes"]
                if s in valid
            )
            if counts:
                modal[film] = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[0][
                    0
                ]
        hits = collections.Counter()
        wrong = collections.Counter()
        within_one = 0
        for u in scored:
            key = (u["participant"], u["session"], u["row"])
            before = previous.get((u["participant"], u["row"]))
            carried = max(before["scenes"]) if before and before["scenes"] else None
            in_film = [s for s in ordered if film_of[s] == u["film"]]
            guesses = {
                "modal_scene_per_film_given_film": modal.get(u["film"]),
                "lexical_jaccard": jaccard(key, ordered),
                "lexical_tfidf": cosine(key, ordered),
                "lexical_tfidf_given_film": cosine(key, in_film),
                "copy_forward_oracle": carried,
            }
            for name, guess in guesses.items():
                hits[name] += guess in u["scenes"]
            hits["order_oracle"] += carried is not None and bool(
                {carried, carried + 1} & u["scenes"]
            )
            answer = ff_score.normalise(predicted[key], film_of, valid)
            if answer["kind"] != "scene":
                continue
            distance = min(abs(answer["scene"] - s) for s in u["scenes"])
            hits["mapper"] += distance == 0
            within_one += distance <= 1 and answer["film"] == u["film"]
            if distance:
                wrong["n"] += 1
                wrong["same_film"] += answer["film"] == u["film"]
                wrong["adjacent"] += distance == 1 and answer["film"] == u["film"]
        n = len(scored)
        report["sessions"][session] = {
            "n": n,
            "rates": {name: hits[name] / n for name in sorted(hits)},
            "hits": dict(sorted(hits.items())),
            "wrong_scene": dict(wrong),
            "within_one_scene": {"hits": within_one, "rate": within_one / n},
        }
    with open(output, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=1, sort_keys=True)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
