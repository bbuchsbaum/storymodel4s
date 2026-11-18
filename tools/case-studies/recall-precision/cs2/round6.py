"""Development-only screen for adjacent-scene adjudication. Run with python -m cs2.round6."""
import json
import math
import tempfile
from dataclasses import dataclass
from collections import Counter, defaultdict
from pathlib import Path
from statistics import mean

from cs2 import baselines
from cs2 import descriptive as ds
from cs2.run import bins_of
from metrics import tfidf_cosine
from cs2 import round4, round5, scorer


def boundary_pair(f, row, valid, comp, clock):
    works = sorted((f.p_work(w), w) for w, scenes in valid.items() if scenes)
    if not works or (len(works) > 1 and works[-1][0] == works[-2][0]):
        return None
    pw, w = works[-1]
    if pw <= 0 or not clock.single.get(w):
        return None
    scenes = valid[w]
    ranked = sorted(range(len(scenes)), key=lambda i: -f.p_set_and_work({scenes[i]}, w))
    if len(ranked) < 2:
        return None
    i, j = ranked[:2]
    a, b = scenes[i], scenes[j]
    pa, pb = f.p_set_and_work({a}, w), f.p_set_and_work({b}, w)
    if abs(i - j) != 1 or pa == pb or (pa - pb) / pw > 0.20:
        return None
    if len(ranked) > 2 and pb == f.p_set_and_work({scenes[ranked[2]]}, w):
        return None
    occurrence, start, end = clock.scene[b]
    for key, weight in row.mass.items():
        if key[:2] != ("at", "unit") or weight <= 0:
            continue
        unit = key[2]
        if comp.unit[unit].scene == a and clock.unit[unit][0] == occurrence:
            if ds.gap(*clock.unit[unit][1:], [(start, end)]) <= 5:
                return w, a, b
    return None


@dataclass(frozen=True)
class PairForecast:
    base: object
    work: str
    a: object
    b: object
    shift: float

    def p_work(self, w):
        return self.base.p_work(w)

    def p_nonfilm(self):
        return self.base.p_nonfilm()

    def p_set_and_work(self, scenes, w):
        q = self.base.p_set_and_work(scenes, w)
        if w == self.work:
            q += self.shift * (int(self.a in scenes) - int(self.b in scenes))
        return q


def lexical_choice(f, pair, tokens, docs):
    """Mix equal parts existing pair odds and token cosine; no timing or order features."""
    w, a, b = pair
    sim = tfidf_cosine(tokens, {a: docs[a], b: docs[b]})
    total = sim[a] + sim[b]
    if total == 0:
        return f
    pa = f.p_set_and_work({a}, w)
    mass = f.p_set_and_work({a, b}, w)
    return PairForecast(f, w, a, b, (mass * sim[a] / total - pa) / 2)


def screen(x, sample, pick):
    fmap, rows, mixes = round5.producer_parts(x, x.account(sample))
    clock = ds.film_clock(x.comp)
    flags = {
        k: [
            boundary_pair(f, r, x.valid, x.comp, clock)
            for f, r in zip(fmap[k], rows[k], strict=True)
        ]
        for k in x.keys
    }
    base, decoder = round5.order_forecasts(x, fmap, mixes, pick)
    scores = x.score(base)
    docs = baselines.scene_tokens(x.comp, x.A.producer_unit_texts(), x.cfg)
    revised = {}
    for key in x.keys:
        p, session = key
        words = x.A.session_words(p, session)
        bins = bins_of([g for g in x.gold[p] if g.session == session])
        revised[key] = []
        for interval, pair, f in zip(bins, flags[key], base[key], strict=True):
            if pair:
                tokens = [
                    t
                    for word in words
                    if interval.contains(word.onset)
                    for t in baselines.tokens(word.text)
                ]
                f = lexical_choice(f, pair, tokens, docs)
            revised[key].append(f)
    changed_scores = x.score(revised)
    counts = Counter(
        dict.fromkeys(
            (
                "flagged_misses",
                "flagged_hits",
                "flagged_adjacent_misses",
                "flagged_with_gold_candidate",
                "caught_with_gold_candidate",
            ),
            0,
        )
    )
    bits = defaultdict(list)
    seconds = []
    unordered = []
    for g, f, pair, s in zip(
        x.rows(), round5.flat(x, base), round5.flat(x, flags), scores, strict=True
    ):
        counts["rows"] += 1
        counts["flags"] += pair is not None
        if s.scene_bits is None:
            continue
        counts["scorable"] += 1
        counts["misses"] += not s.scene_hit
        counts["adjacent_misses"] += not s.scene_hit and s.within1
        error = ds.time_error(f, g, x.valid, clock)
        if error is not None:
            seconds.append((g.participant, error))
        if s.gold_step in scorer.OUT_OF_ORDER:
            unordered.append((g.participant, float(s.scene_hit)))
        gain = 0.0
        if pair:
            counts["scorable_flags"] += 1
            counts["flagged_hits"] += s.scene_hit
            counts["flagged_misses"] += not s.scene_hit
            counts["flagged_adjacent_misses"] += not s.scene_hit and s.within1
            w, a, b = pair
            gold = set(g.scenes).intersection(x.valid[g.work])
            inside = gold.intersection((a, b)) if w == g.work else set()
            if inside:
                counts["flagged_with_gold_candidate"] += 1
                counts["caught_with_gold_candidate"] += not s.scene_hit
                # Perfect pair choice may help even an already-correct row.
                before = f.p_set_and_work(gold, w)
                extra = f.p_set_and_work({a, b} - inside, w)
                gain = math.log2((before + extra) / before)
        bits[g.participant].append(gain)

    def clustered(pairs):
        by = defaultdict(list)
        for p, v in pairs:
            by[p].append(v)
        return mean(map(mean, by.values())) if by else None

    updated_seconds = [
        (g.participant, ds.time_error(f, g, x.valid, clock))
        for g, f in zip(x.rows(), round5.flat(x, revised), strict=True)
        if ds.gold_stretches(g, x.valid, clock) is not None
    ]
    updated_order = [
        (s.participant, float(s.scene_hit))
        for s in changed_scores
        if s.gold_step in scorer.OUT_OF_ORDER
    ]
    return dict(
        counts=dict(counts),
        scene_bits=round4.tmean(scores, "scene_bits"),
        lexical_scene_bits=round4.tmean(changed_scores, "scene_bits"),
        oracle_gain_bits=mean(map(mean, bits.values())),
        film_time_error_seconds=clustered(seconds),
        lexical_film_time_error_seconds=clustered(updated_seconds),
        out_of_order_top_scene=clustered(unordered),
        lexical_out_of_order_top_scene=clustered(updated_order),
    ), decoder


def main():
    # Account rebuilding can write private transcripts. Keep them in the temporary run.
    with tempfile.TemporaryDirectory() as work:
        round4.WORK = Path(work)
        ff = round4.Corpus("filmfestival")
        # Compare to the archived round-5 account, including its conversion rule.
        ff.rules = dict(ff.rules, innermost_tie="first")
        s1, decoder = screen(ff, 1, round5.lopo_alpha)
        s2, _ = screen(ff, 2, round5.lopo_alpha)
        sh = round4.Corpus("sherlock")
        sh.rules = dict(sh.rules, innermost_tie="first")
        sh1, _ = screen(sh, 1, lambda d: dict.fromkeys(sh.participants, decoder._all))
        print(
            json.dumps(
                dict(filmfestival_s1=s1, filmfestival_s2=s2, sherlock=sh1), indent=2
            )
        )


if __name__ == "__main__":
    main()
