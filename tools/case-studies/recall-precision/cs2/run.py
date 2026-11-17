"""Case study 2 runs without model calls: reference-forecast baselines per corpus, and the Sherlock
smoke run of the mapping object from case study 1's recorded content-local answers.

Usage (from tools/case-studies/recall-precision):
  python -m cs2.run baselines CORPUS OUT_DIR      # development participants with gold only
  python -m cs2.run sherlock-smoke OUT_DIR
  python -m cs2.run ff-run OUT_DIR --export DIR --seed N --salt S --sample K \\
      [--sessions development|p:s,...] [--arm A] [--jobs N] [--dry|--cache-only]

Outputs (under the ignored data root): summary.json, rows.tsv (keys and bits, no text) and
manifest.json with the commit SHA, the config digest and input digests. Recall text is read into
memory for the TF-IDF reference forecast only and is never written.
"""

from __future__ import annotations

import csv
import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import data as D  # noqa: E402
from cs2 import baselines as B  # noqa: E402
from cs2 import scorer as SC  # noqa: E402
from cs2.account import (  # noqa: E402
    Account,
    Hypothesis,
    Member,
    Reference,
    RefSample,
    Shape,
    StateSample,
    Uncovered,
    TalkSample,
    WordCell,
    WorkReference,
)
from cs2.projections import Bin, LocationDistribution, word_ledger  # noqa: E402

WORK_APPLICABLE = {"sherlock": False, "filmfestival": True}


def sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def git_state():
    sha = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=HERE, capture_output=True, text=True
    ).stdout.strip()
    dirty = subprocess.run(  # the harness and every module the producer imports
        ["git", "status", "--porcelain", "--untracked-files=all", "--", ".",
         str(HERE.parents[1] / "sherlock_mapper")],
        cwd=HERE,
        capture_output=True,
        text=True,
    ).stdout.strip()
    return dict(commit=sha, harness_dirty=bool(dirty), dirty_paths=dirty.splitlines())


def gold_by_participant(A, participants):
    out = {}
    for p in participants:
        rows = A.gold_rows(p)
        if rows:
            out[p] = rows
    return out


def by_session(rows):
    out = {}
    for g in rows:
        out.setdefault(g.session, []).append(g)
    return out


def bins_of(rows):
    return [Bin(g.lo, g.hi, g.closed) for g in rows]


BASELINES = ("duration", "lopo", "gold_context_ceiling", "gold_context_fitted", "tfidf",
             "hmm_tfidf")


def resolve_rules(cfg, which="new", overrides=None):
    rules = dict(cfg["rules"] if which == "new" else cfg["old_rules"])
    rules.update(overrides or {})
    return rules


def baseline_forecasts(A, comp, gold, cfg, rules=None):
    """dur_by_participant, {name: {(participant, session): [forecast per row]}}.

    rules['film_level_baseline'] 'lopo' (owner, 2026-11-17): every forecast's non-film level is
    the participant's LOPO non-film rate; 'flat': the config's nonfilm_fallback_rate.
    'gold_context_ceiling' is the previous order oracle, relabelled; it reads the previous gold
    row, as does 'gold_context_fitted' (LOPO-fitted rates). 'hmm_tfidf' reads recall words only."""
    rules = rules or resolve_rules(cfg)
    dur0 = B.duration_prior(comp, cfg)
    docs = B.scene_tokens(comp, A.producer_unit_texts(), cfg)
    out = {k: {} for k in BASELINES}
    durs = {}
    for p, rows in gold.items():
        rate = (B.lopo_nonfilm_rate(gold, p, cfg) if rules["film_level_baseline"] == "lopo"
                else cfg["nonfilm_fallback_rate"])
        dur = B.with_nonfilm(dur0, rate)
        durs[p] = dur
        lo = B.with_nonfilm(B.lopo(comp, gold, p, cfg), rate)
        gc = B.fit_gold_context(comp, gold, p, cfg)
        hm = B.fit_hmm(comp, gold, p, cfg)
        for s, rs in by_session(rows).items():
            words = A.session_words(p, s)
            out["duration"][(p, s)] = [dur] * len(rs)
            out["lopo"][(p, s)] = [lo] * len(rs)
            out["gold_context_ceiling"][(p, s)] = B.order_oracle(comp, rs, cfg, dur)
            out["gold_context_fitted"][(p, s)] = B.gold_context_fitted(comp, rs, cfg, dur, gc)
            toks = [[t for w in words if b.contains(w.onset) for t in B.tokens(w.text)]
                    for b in bins_of(rs)]
            out["tfidf"][(p, s)] = B.tfidf(comp, toks, docs, cfg, dur)
            out["hmm_tfidf"][(p, s)] = B.hmm_tfidf(comp, toks, docs, cfg, dur, hm)
    return durs, out


def row_frame_mixes(acc, key, rows):
    """Per gold row: the mean over its words of their unit's pass-1 frame (work -> weight);
    words without a framed unit contribute nothing."""
    p, s = key
    cells = acc.words[key]
    out = []
    for b in bins_of(rows):
        inside = [c for c in cells if b.contains(c.onset)]
        mix = {}
        for c in inside:
            for w, wt in acc.framing_work.get((p, s, c.unit), ()):
                mix[w] = mix.get(w, 0.0) + wt / len(inside)
        out.append(mix)
    return out


def window_length(all_gold):
    """Declared gold-blind window: the median gold-row duration of the corpus's development gold."""
    d = sorted(g.hi - g.lo for rs in all_gold.values() for g in rs)
    return d[len(d) // 2]


def windowed_gold(A, all_gold, length):
    """Fixed windows of `length` seconds over each coded session's words (first to last onset).
    Each window takes the label of the gold row it overlaps most; windows overlapping no gold
    row are dropped. The windows, not the gold spans, are the bins."""
    out = {}
    from cs2.source import GoldRow

    for p, rows in all_gold.items():
        for s, rs in by_session(rows).items():
            ons = [w.onset for w in A.session_words(p, s) if w.onset is not None]
            lo, k = min(ons), 0
            while lo <= max(ons):
                hi = lo + length
                best = max(rs, key=lambda g: min(hi, g.hi) - max(lo, g.lo))
                if min(hi, best.hi) - max(lo, best.lo) > 0:
                    out.setdefault(p, []).append(GoldRow(p, s, k, lo, hi, False, best.cls,
                                                         best.work, best.scenes, best.nonfilm))
                    k += 1
                lo = hi
    return out


def score_corpus(A, comp, acc, sessions, dev, cfg, rules, wa, windows=None):
    """The yardstick for one producer account: baselines, producer and pass-1-only forecasts.
    windows: None (gold-row spans) or a window length in seconds ('median' = declared median
    gold-row duration), the gold-blind windowing sensitivity check."""
    all_gold = gold_by_participant(A, dev)
    if windows is not None:
        length = window_length(all_gold) if windows == "median" else float(windows)
        all_gold = windowed_gold(A, all_gold, length)
        cfg = dict(cfg, window_seconds=length)
    gold = {}
    for p, s in sessions:
        rows = [g for g in all_gold.get(p, []) if g.session == s]
        if rows:
            gold.setdefault(p, []).extend(rows)
    durs, fc_all = baseline_forecasts(A, comp, all_gold, cfg, rules)
    keys = sorted({(p, g.session) for p, rs in gold.items() for g in rs})
    named = {k: score_map(comp, gold, {x: v[x] for x in keys}, cfg, wa)
             for k, v in fc_all.items()}
    pmap, p1map = {}, {}
    for p, s in keys:
        rows = [g for g in gold[p] if g.session == s]
        ld = LocationDistribution.from_words(acc, acc.words[(p, s)], bins_of(rows),
                                             cfg["allocation"], rules["cross_scene_broad"])
        mixes = row_frame_mixes(acc, (p, s), rows)
        fbs = [B.frame_fallback(durs[p], m, cfg["frame_floor"]) for m in mixes]
        p1map[(p, s)] = fbs
        pmap[(p, s)] = SC.producer_forecasts(
            ld, durs[p], cfg, fbs if rules["fallback"] == "frame-film" else None)
    named["producer"] = score_map(comp, gold, pmap, cfg, wa)
    if acc.framing_work:
        named["pass1_only"] = score_map(comp, gold, p1map, cfg, wa)
    words = {f"{p}:{s}": word_ledger(acc.words[(p, s)],
                                     bins_of([g for g in gold[p] if g.session == s]))
             for p, s in keys}
    # null-producer check through the same path (frame fallback included): no references and
    # no frames must score the duration forecast exactly on every term
    null = Account(comp, {}, {k: [WordCell(c.index, c.onset, c.unit, Uncovered(c.unit))
                                  for c in acc.words[k]] for k in keys})
    nmap = {}
    for p, s in keys:
        rows = [g for g in gold[p] if g.session == s]
        ld = LocationDistribution.from_words(null, null.words[(p, s)], bins_of(rows),
                                             cfg["allocation"], rules["cross_scene_broad"])
        fbs = [B.frame_fallback(durs[p], m, cfg["frame_floor"]) for m in row_frame_mixes(null, (p, s), rows)]
        nmap[(p, s)] = SC.producer_forecasts(
            ld, durs[p], cfg, fbs if rules["fallback"] == "frame-film" else None)
    nsc = score_map(comp, gold, nmap, cfg, wa)
    diff = 0.0
    for a, b in zip(nsc, named["duration"]):
        for t in ("film_bits", "work_bits", "scene_bits", "total_bits"):
            x, y = getattr(a, t), getattr(b, t)
            if (x is None) != (y is None):
                raise SystemExit("null-producer check: scored terms differ")
            if x is not None:
                diff = max(diff, abs(x - y))
    checks = dict(null_producer=dict(rows=len(nsc), max_abs_bits_difference=diff,
                                     fallback=rules["fallback"]),
                  window_seconds=cfg.get("window_seconds"))
    return named, words, checks


def score_map(comp, gold, fmap, cfg, work_applicable):
    valid = B.valid_scenes(comp, cfg)
    out = []
    for p, rows in gold.items():
        for s, rs in by_session(rows).items():
            out += SC.score_rows(rs, fmap[(p, s)], comp, valid, work_applicable)
    return out


def write_rows(path, named_scores):
    with open(path, "w", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(
            [
                "forecast",
                "participant",
                "session",
                "row",
                "cls",
                "work",
                "film_bits",
                "work_bits",
                "scene_bits",
                "total_bits",
                "top_work",
                "top_scene",
                "scene_hit",
                "located_share",
                "nonwork",
                "abstain",
                "dropped_scenes",
            ]
        )
        for name, scores in named_scores.items():
            for s in scores:
                w.writerow(
                    [
                        name,
                        s.participant,
                        s.session,
                        s.row,
                        s.cls,
                        s.work or "",
                        _fmt(s.film_bits),
                        _fmt(s.work_bits),
                        _fmt(s.scene_bits),
                        _fmt(s.total_bits),
                        s.top_work or "",
                        "" if s.top_scene is None else s.top_scene,
                        "" if s.scene_hit is None else int(s.scene_hit),
                        _fmt(s.located_share),
                        _fmt(s.nonwork),
                        _fmt(s.abstain),
                        s.dropped_scenes,
                    ]
                )


def _fmt(x):
    return (
        "n/a" if x is None or (isinstance(x, float) and math.isnan(x)) else f"{x:.6f}"
    )


def summarize_forecasts(named, cfg, work_applicable, reference="duration"):
    terms = ("film_bits", "work_bits", "scene_bits", "total_bits")
    if not work_applicable:
        terms = ("film_bits", "scene_bits", "total_bits")
    out = {}
    for name, sc in named.items():
        d = {t: SC.summarize(sc, cfg, t) for t in terms}
        for t in d:
            d[t].pop("per_participant")
        if name != reference:
            d["skill_vs_" + reference] = {
                t: SC.skill(sc, named[reference], cfg, t) for t in terms
            }
        spec = [s for s in sc if s.scene_hit is not None]
        d["top_scene_in_gold_set"] = dict(
            n=len(spec),
            share=sum(s.scene_hit for s in spec) / len(spec) if spec else None,
        )
        out[name] = d
    return out


def abstain_check(A, comp, gold, cfg, durs, work_applicable):
    """A producer that abstains on every word must score the fallback on every row."""
    words, fmap = {}, {}
    for p, rows in gold.items():
        for s, rs in by_session(rows).items():
            cells = [
                WordCell(w.index, w.onset, "", Uncovered(""))
                for w in A.session_words(p, s)
            ]
            words[(p, s)] = cells
    acc = Account(comp, {}, words)
    for (p, s), cells in words.items():
        rs = by_session(gold[p])[s]
        ld = LocationDistribution.from_words(acc, cells, bins_of(rs))
        fmap[(p, s)] = SC.producer_forecasts(ld, durs[p], cfg)
    prod = score_map(comp, gold, fmap, cfg, work_applicable)
    base = score_map(
        comp, gold, {k: [durs[k[0]]] * len(v) for k, v in fmap.items()}, cfg, work_applicable
    )
    diff = 0.0
    for a, b in zip(prod, base):
        for t in ("film_bits", "work_bits", "scene_bits", "total_bits"):
            x, y = getattr(a, t), getattr(b, t)
            if (x is None) != (y is None):
                raise SystemExit("abstain check: scored terms differ")
            if x is not None:
                diff = max(diff, abs(x - y))
    return dict(rows=len(prod), max_abs_bits_difference=diff)


def recall_ledger(A, gold):
    """Denominators: words vs utterances (gold rows), words inside vs outside gold rows."""
    t = dict(sessions=0, words=0, words_with_onset=0, words_in_gold_rows=0,
             words_outside_gold_rows=0, gold_rows=0)
    by_cls = {}
    for p, rows in gold.items():
        for s, rs in by_session(rows).items():
            t["sessions"] += 1
            t["gold_rows"] += len(rs)
            bins = bins_of(rs)
            for w in A.session_words(p, s):
                t["words"] += 1
                if w.onset is None:
                    continue
                t["words_with_onset"] += 1
                inside = any(b.contains(w.onset) for b in bins)
                t["words_in_gold_rows" if inside else "words_outside_gold_rows"] += 1
            for g in rs:
                d = by_cls.setdefault(g.cls, dict(rows=0))
                d["rows"] += 1
    t["rows_by_class"] = by_cls
    return t


def manifest(out, kind, cfg_path, inputs, extra, state=None, model_calls=0):
    """`state` is the git state captured before any model call (default: now)."""
    m = dict(
        schema="storymodel4s.case-study-2.run-manifest/v1",
        kind=kind,
        **(state or git_state()),
        config=dict(path=str(cfg_path), sha256=sha256(cfg_path)),
        inputs={str(k): sha256(k) for k in inputs if Path(k).exists()},
        model_calls=model_calls,
        **extra,
    )
    (out / "manifest.json").write_text(json.dumps(m, indent=1) + "\n")


def cmd_baselines(corpus, out):
    out.mkdir(parents=True, exist_ok=True)
    cfg = SC.load_config()
    A = D.adapter(corpus)
    comp = A.composition()
    wa = WORK_APPLICABLE[corpus]
    gold = gold_by_participant(A, A.development())
    dur, fc = baseline_forecasts(A, comp, gold, cfg, resolve_rules(cfg))
    named = {k: score_map(comp, gold, v, cfg, wa) for k, v in fc.items()}
    summary = dict(
        evidence="development data, engineering numbers; validation = the frozen-six run",
        corpus=corpus,
        participants=sorted(gold),
        sessions=sorted({(p, g.session) for p, rs in gold.items() for g in rs}),
        work_identity="scored" if wa else "not applicable",
        rows=SC.strata(named["duration"], comp, wa)["rows"],
        forecasts=summarize_forecasts(named, cfg, wa),
        abstain_everything=abstain_check(A, comp, gold, cfg, dur, wa),
        valid_scenes=sum(len(v) for v in B.valid_scenes(comp, cfg).values()),
        dropped_title_scene_refs=sum(s.dropped_scenes for s in named["duration"]),
        recall_ledger=recall_ledger(A, gold),
    )
    if hasattr(A, "gold_ledger"):
        summary["gold_reader_ledger"] = A.gold_ledger(sorted(gold))
    if corpus == "filmfestival":
        summary["gold_numbering_check"] = A.check_gold_numbering(sorted(gold))
    (out / "summary.json").write_text(json.dumps(summary, indent=1) + "\n")
    write_rows(out / "rows.tsv", named)
    inputs = []
    if corpus == "filmfestival":
        inputs = [
            A.GOLD,
            A.CODES,
            A.PARTITION,
            A.FF / "derived" / "annotation-JL.tsv",
            A.FF / "FILMS.md",
        ]
    else:
        inputs = [A.GOLD, A.RULE, D.DATA / "study/recall-to-video/partition.json"]
    manifest(
        out,
        f"baselines/{corpus}",
        SC.CONFIG,
        inputs,
        dict(participants=sorted(gold), composition_rules=comp.rules),
    )
    print(json.dumps(summary["forecasts"], indent=1))
    print("abstain check", summary["abstain_everything"])


# ---------------------------------------------------------------- Sherlock smoke

# Declared state mapping (owner, 2026-11-16), as for FilmFestival: a fragment is a ledger count;
# metamemory and commentary are inside the one framed work (Sherlock has one work), so they are
# work-level references to it.
WORK_LEVEL_TALK = ("metamemory", "commentary")
LIBRARY = D.OUT / "parity" / "library-v4"
ANSWERS = D.OUT / "llm" / "llm-local-v2-a"


def hypotheses(form, alts):
    out = []
    for a in alts:
        ivs = [tuple(sorted((int(x), int(y)))) for x, y in a["iv"]]
        rows = [frozenset(f"r{i}" for i in range(lo, hi + 1)) for lo, hi in ivs]
        if form in ("specific", "extended"):
            h = Hypothesis(Shape.SINGLE, (frozenset().union(*rows),))
        elif form == "summary":
            h = Hypothesis(Shape.BROAD, (frozenset().union(*rows),))
        elif form == "repeated":
            h = Hypothesis(Shape.SINGLE, tuple(rows))
        elif form == "joint":
            h = Hypothesis(Shape.JOINT, tuple(rows))
        else:
            raise SystemExit(f"unknown form {form}")
        out.append(Member(h, float(a["w"])))
    return tuple(out)


def sherlock_account(A, comp, names, rules=None):
    """CS1 recorded answers as an account. rules['talk'] 'orthogonal': metamemory, commentary and
    fragments are non-addressed talk (TalkSample); 'state-map-v1': work-level references."""
    talk_rule = (rules or {}).get("talk", "orthogonal")
    refs, words, ledger = {}, {}, dict(converted=0, refused=0, forms={})
    seen_work = set()
    for name in names:
        short = name.split("_")[0]
        rtab = list(
            csv.DictReader(
                open(LIBRARY / f"{short}_desc-libraryparity_references.tsv"),
                delimiter="\t",
            )
        )
        ans = {
            u["id"]: u
            for u in json.loads((ANSWERS / f"{name}.json").read_text())["units"]
        }
        units = D.units(name)
        for r in rtab:
            if r["status"] != "converted":
                ledger["refused"] += 1
                continue
            u = ans[r["unit_id"]]
            k = int(r["ref_index"])
            rr = u["refs"][k]
            if rr.get("form") != r["form"]:
                raise SystemExit(f"form mismatch at {r['unit_id']}:{k}")
            rid = f"{short}:{r['unit_id']}:{k}"
            refs[rid] = Reference(
                rid,
                hypotheses(r["form"], rr["alts"]),
                condition="llm-local-v2-a/library-v4",
            )
            ledger["converted"] += 1
            ledger["forms"][r["form"]] = ledger["forms"].get(r["form"], 0) + 1
        lw = list(
            csv.DictReader(
                open(LIBRARY / f"{short}_desc-libraryparity_words.tsv"), delimiter="\t"
            )
        )
        sw = A.session_words(name)
        rw = D.recall_words(name)
        if len(lw) != len(sw):
            raise SystemExit(
                f"{name}: {len(lw)} library words vs {len(sw)} recall words"
            )
        cells = []
        for i, (x, w, raw) in enumerate(zip(lw, sw, rw)):
            if abs(float(x["onset"]) - raw.openneuro) > 1e-6:
                raise SystemExit(f"{name}: word {i} onset differs from the library's")
            ui = int(x["unit_index"])
            uid = units[ui].id if ui >= 0 else ""
            s = x["sample"]
            if s.startswith("na:"):
                talk = ans[uid]["talk"]
                if talk_rule == "orthogonal":
                    sample = TalkSample(uid, talk)
                    ledger["non_addressed_words"] = ledger.get("non_addressed_words", 0) + 1
                elif talk in WORK_LEVEL_TALK:
                    rid = f"{short}:{uid}:work"
                    refs.setdefault(rid, WorkReference(rid, A.WORK, 1.0,
                                                       condition="llm-local-v2-a/state-map"))
                    sample = RefSample(rid)
                    ledger["work_level_units"] = ledger.get("work_level_units", 0) + (
                        rid not in seen_work)
                    seen_work.add(rid)
                elif talk == "fragment":
                    sample = Uncovered(uid)
                    ledger["fragment_words"] = ledger.get("fragment_words", 0) + 1
                else:
                    raise SystemExit(f"unmapped talk {talk!r}")
            elif s == "-1":
                sample = Uncovered(uid)
            else:
                su, sr = s.split(":")
                if int(su) != ui:
                    raise SystemExit(f"{name}: word {i} samples another unit")
                sample = RefSample(f"{short}:{uid}:{sr}")
            cells.append(WordCell(i, w.onset, uid, sample))  # Princeton clock
        words[(name, "recall")] = cells
    return Account(
        comp,
        refs,
        words,
        condition="llm-local-v2-a (opus, seed 20261117, "
        "context two, prompt v2) converted by library-v4",
    ), ledger


def cmd_sherlock_smoke(out, which="new", eps=None, overrides=None, windows=None):
    """Score the CS1 recorded answers (no calls) under the given rules."""
    out.mkdir(parents=True, exist_ok=True)
    cfg = SC.load_config()
    if eps is not None:
        cfg["epsilon"] = eps
    rules = resolve_rules(cfg, which, overrides)
    A = D.adapter("sherlock")
    comp = A.composition()
    names = [n for n in A.development()
             if (LIBRARY / f"{n.split('_')[0]}_ledger.json").exists()]
    acc, conv = sherlock_account(A, comp, names, rules)
    named, words, checks = score_corpus(A, comp, acc, [(n, "recall") for n in names], names,
                                        cfg, rules, False, windows)
    write_summary(out, "sherlock", named, words, comp, cfg, rules, False, conv,
                  extra=dict(producer=acc.condition, checks=checks))
    unit_measures_out(out, acc, comp, rules)
    inputs = [A.GOLD, A.RULE, LIBRARY / "manifest.json"]
    inputs += [ANSWERS / f"{n}.json" for n in names]
    inputs += [LIBRARY / f"{n.split('_')[0]}_desc-libraryparity_{k}.tsv"
               for n in names for k in ("references", "words")]
    manifest(out, "sherlock-smoke", SC.CONFIG, inputs,
             dict(producer=acc.condition, rules=rules, epsilon=cfg["epsilon"],
                  composition_rules=comp.rules))


def write_summary(out, corpus, named, words, comp, cfg, rules, wa, conv, extra=None):
    fsum = summarize_forecasts(named, cfg, wa)
    terms = ("film_bits", "work_bits", "scene_bits", "total_bits") if wa else (
        "film_bits", "scene_bits", "total_bits")
    fsum["producer"].update({f"skill_vs_{b}": {t: SC.skill(named["producer"], named[b], cfg, t)
                                               for t in terms}
                             for b in named if b not in ("producer", "duration")})
    st = SC.strata(named["producer"], comp, wa)
    shapes = (conv or {}).get("shapes") or {}
    cases = (conv or {}).get("broad_cases") or {}
    modes = (conv or {}).get("modes") or {}
    nb = sum(cases.values())
    st["localizability"]["pass2_mode_share"] = (
        {k: round(v / sum(modes.values()), 4) for k, v in modes.items()} if modes else None)
    st["localizability"]["cross_scene_broad_share"] = (
        round((cases.get("scene_set", 0) + cases.get("work", 0)) / nb, 4) if nb else None)
    summary = dict(
        evidence="development data, engineering numbers; validation = the frozen-six run",
        corpus=corpus, rules=rules, epsilon=cfg["epsilon"],
        rows=st["rows"], forecasts=fsum, producer_strata=st,
        baseline_strata={k: SC.strata(v, comp, wa) for k, v in named.items() if k != "producer"},
        words=words, conversion=conv, shapes=shapes, **(extra or {}))
    (out / "summary.json").write_text(json.dumps(summary, indent=1, default=str) + "\n")
    write_rows(out / "rows.tsv", named)
    return summary


def unit_measures_out(out, acc, comp, rules):
    (out / "unit_measures.json").write_text(json.dumps(
        unit_measures(acc, comp, rules["cross_scene_broad"]), indent=0))


def unit_measures(acc, comp, broad_rule="scene-set"):
    """Per recall unit, for between-sample reliability (declared):
    - top_scene: argmax scene of the unit's own words (one closed bin over their onsets), or
      null when no scene mass;
    - alternatives: any reference sampled in the unit has two or more members;
    - log10_extent: mean over the unit's moment references of the weight-averaged seconds of
      their members' support (null without one)."""
    from cs2.account import Hypothesis, Reference, RefSample

    out = {}
    for key, cells in acc.words.items():
        by = {}
        for c in cells:
            if c.unit:
                by.setdefault(c.unit, []).append(c)
        for uid, cs in by.items():
            ons = [c.onset for c in cs if c.onset is not None]
            ld = LocationDistribution.from_words(acc, cs, [Bin(min(ons), max(ons), True)],
                                                 broad_rule=broad_rule)
            row = ld.marginal("scene").rows[0]
            sc = {k[2]: v for k, v in row.mass.items() if k[0] == "at"}
            refs = {c.sample.ref for c in cs if isinstance(c.sample, RefSample)}
            moment = [acc.references[r] for r in refs if isinstance(acc.references[r], Reference)]
            ext = []
            for r in moment:
                ws = [(m.weight, sum(comp.unit[u].duration for u in m.target.support))
                      for m in r.members if isinstance(m.target, Hypothesis)]
                if ws and sum(w for w, _ in ws) > 0:
                    e = sum(w * x for w, x in ws) / sum(w for w, _ in ws)
                    if e > 0:
                        ext.append(math.log10(e))
            out[uid] = dict(
                top_scene=max(sorted(sc), key=lambda k: sc[k]) if sc else None,
                alternatives=any(len(r.members) >= 2 for r in moment),
                log10_extent=sum(ext) / len(ext) if ext else None,
            )
    return out


def cmd_ff_run(args):
    """Two-level producer on selected development sessions, pooled; then the yardstick.

    Arguments: OUT --export DIR --seed N --salt S --sample K
    [--sessions development | p:s,p:s,...] [--dry]. Without --dry this makes model calls."""
    from cs2 import ff_producer as P

    P.CACHE_ONLY = "--cache-only" in args
    state = git_state()  # before any model call
    opt = {}
    rest = []
    it = iter(args)
    for a in it:
        if a in ("--export", "--seed", "--salt", "--sample", "--sessions", "--arm", "--jobs",
                 "--corpus", "--rules", "--talk-work-variant", "--outline-order", "--epsilon",
                 "--windows", "--rendering", "--layout", "--pass1", "--mismatch-rule"):
            opt[a[2:].replace("-", "_")] = next(it)
        elif a == "--dry":
            opt["dry"] = True
        elif a == "--cache-only":
            opt["cache_only"] = True
        else:
            rest.append(a)
    corpus = opt.get("corpus", "filmfestival")
    for k in ("seed", "salt", "sample") + (("export",) if corpus == "filmfestival" else ()):
        if k not in opt:
            raise SystemExit(f"--{k} is required")
    out = Path(rest[0])
    A = D.adapter(corpus)
    wa = WORK_APPLICABLE[corpus]
    dev = A.development()
    if opt.get("sessions", "development") == "development":
        sessions = [(p, s) for p in dev for s in A.sessions(p)]
    else:
        sessions = [tuple(x.split(":")) for x in opt["sessions"].split(",")]
    for p, _ in sessions:
        if p not in dev:
            raise SystemExit(f"{p} is not a development participant")
    seed, sample = int(opt["seed"]), int(opt["sample"])
    export = Path(opt["export"]) if "export" in opt else None
    cfg = SC.load_config()
    if "epsilon" in opt:
        cfg["epsilon"] = float(opt["epsilon"])
    overrides = ({"talk_work_variant": opt["talk_work_variant"]}
                 if "talk_work_variant" in opt else {})
    if "mismatch_rule" in opt:
        overrides["mismatch_rule"] = opt["mismatch_rule"]
    rules = resolve_rules(cfg, opt.get("rules", "new"), overrides)
    P.BROAD_RULE = rules["cross_scene_broad"]
    acc, ledger, log, conds = P.run(A, sessions, export, out, seed, opt["salt"],
                                    sample, dry=bool(opt.get("dry")),
                                    arm=opt.get("arm", "coverage"),
                                    jobs=int(opt.get("jobs", 1)), rules=rules,
                                    outline_order=opt.get("outline_order", "part-order"),
                                    rendering=opt.get("rendering", "plain"),
                                    layout=opt.get("layout", "per-call"),
                                    pass1=opt.get("pass1", "v1"))
    calls = P.call_ledger(log)
    extra = dict(
        participants=sorted({p for p, _ in sessions}), sessions=[f"{p}:{s}" for p, s in sessions],
        conditions=conds, call_digests=[{k: c[k] for k in ("label", "condition", "fresh",
                                                           "rendered_prompt_sha256")}
                                        for c in log],
        calls=calls, export_index_sha256=ledger["export_index_sha256"],
        batches=ledger["batches"], dry=bool(opt.get("dry")), arm=opt.get("arm", "coverage"),
        cache_only=bool(opt.get("cache_only")),
        word_clock=A.WORD_CLOCK_RECEIPT,
        gold_clock=(A.gold_clock_receipt(sorted({p for p, _ in sessions}))
                    if hasattr(A, "gold_clock_receipt") else None),
        corpus=corpus, rules=rules, epsilon=cfg["epsilon"],
        outline_order=opt.get("outline_order", "part-order"),
        rendering=opt.get("rendering", "plain"), layout=opt.get("layout", "per-call"),
        pass1=opt.get("pass1", "v1"),
        cost_note="cost_usd is the CLI's list-price estimate (notional); calls run on the owner's "
        "subscription through claude -p",
        condition_id_note="condition ids changed at f98d2297a only because the renderer digest "
        "covers film_outline's source, which gained the outline_order rule; the rendered "
        "FilmFestival prompts are byte-identical (cache-only replays reproduce every row)",
    )
    if corpus == "filmfestival":
        inputs = [A.GOLD, A.CODES, A.PARTITION, A.FF / "derived" / "annotation-JL.tsv",
                  export / "index.json"]
        inputs += [A.FF / "derived" / "recall" / f"{p}_{s}.csv" for p, s in sessions]
    else:
        inputs = [A.GOLD, A.RULE, D.DATA / "study/recall-to-video/partition.json"]
        inputs += [LIBRARY / f"{p.split('_')[0]}_desc-libraryparity_words.tsv" for p, _ in sessions]
    if acc is None:
        manifest(out, "ff-run (dry)", SC.CONFIG, inputs, extra, state, calls["model_calls"])
        print(json.dumps(dict(batches=ledger["batches"], calls=calls,
                              sessions=ledger["sessions"]), indent=1, default=str))
        return
    comp = A.producer_composition()
    named, words, checks = score_corpus(A, comp, acc, sessions, dev, cfg, rules, wa,
                                        opt.get("windows"))
    write_summary(out, corpus, named, words, comp, cfg, rules, wa, ledger["conversion"],
                  extra=dict(sessions=extra["sessions"], ledger=ledger, calls=calls,
                             checks=checks))
    unit_measures_out(out, acc, comp, rules)
    manifest(out, "ff-run", SC.CONFIG, inputs, dict(extra, producer=acc.condition), state,
             calls["model_calls"])


def main(argv):
    if argv[:1] == ["baselines"] and len(argv) == 3:
        cmd_baselines(argv[1], Path(argv[2]))
    elif argv[:1] == ["ff-run"] and len(argv) >= 2:
        cmd_ff_run(argv[1:])
    elif argv[:1] == ["sherlock-smoke"] and len(argv) >= 2:
        a = argv[1:]
        which = a[a.index("--rules") + 1] if "--rules" in a else "new"
        eps = float(a[a.index("--epsilon") + 1]) if "--epsilon" in a else None
        win = a[a.index("--windows") + 1] if "--windows" in a else None
        cmd_sherlock_smoke(Path(a[0]), which, eps, None, win)
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
