"""Fail-closed scoring entry point for the CS2 sealed-six evaluation.

Baseline fitting reads development gold. Evaluation uses the adapter and accounts supplied by the registered caller.
The registered producer supplies two already-authorized forecast maps; this module combines their
probabilities, scores them against caller-supplied frozen training-only baselines, and applies the
pre-registered primary and seven-second-window decision rules.
"""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path

from cs2 import baselines as B
from cs2 import run as R
from cs2 import scorer as SC
from cs2.projections import LocationDistribution
from metrics import sign_flip_p


SEALED_SESSIONS = (
    "sub-06:run-01",
    "sub-09:run-01",
    "sub-11:run-01",
    "sub-12:run-01",
    "sub-13:run-01",
    "sub-20:run-01",
)
SCORABLE_PARTICIPANTS = ("sub-09", "sub-11", "sub-12", "sub-13", "sub-20")
REQUIRED_BASELINES = ("duration", "lopo", "gold_context_fitted", "tfidf", "hmm_tfidf")
MEAN_GAIN_BITS = 0.5
TIE_AMENDMENT_CONFIG_SHA256 = (
    "59f3b1708fedd6a7cfa48aec784a5fea65f82e03502057698c8987ed2c72af08"
)


class SealedPreflightError(RuntimeError):
    """A sealed-run receipt, forecast, or denominator violates the declared protocol."""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _frozen_digest(value) -> str:
    """The registered JSON digest recipe (including Python's deterministic string fallback)."""
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, default=str).encode()
    ).hexdigest()


@dataclass(frozen=True)
class FrozenTraining:
    """Development-only baseline fits used unchanged for every sealed participant."""

    cfg: dict
    comp: object
    gold: dict
    docs: dict
    duration: object
    lopo: object
    nonfilm_rate: float
    gold_context: dict
    hmm: dict
    receipt: dict


def _digests_path(adapter) -> Path:
    return (
        Path(adapter.DATA)
        / "study/recall-precision/cs2/prereg-20261117/frozen-digests.json"
    )


def freeze_training(adapter, receipt_path: Path | None = None) -> FrozenTraining:
    """Fit all baselines on public development gold, then verify the registered scalar fits.

    The receipt's duration/LOPO digest recipe is canonical JSON with sorted keys and Python's
    deterministic string fallback; all represented fits are checked before they can be used.
    """
    receipt_path = receipt_path or _digests_path(adapter)
    receipt = json.loads(receipt_path.read_text())
    cfg = SC.load_config()
    active_digest = sha256(SC.CONFIG)
    if active_digest != TIE_AMENDMENT_CONFIG_SHA256:
        raise SealedPreflightError(
            "scorer configuration is not the approved tie amendment"
        )
    comp = adapter.composition()
    gold = R.gold_by_participant(adapter, adapter.development())
    sentinel = "__sealed_participant__"
    duration = B.duration_prior(comp, cfg)
    lopo = B.lopo(comp, gold, sentinel, cfg)
    nonfilm_rate = B.lopo_nonfilm_rate(gold, sentinel, cfg)
    gold_context = B.fit_gold_context(comp, gold, sentinel, cfg)
    hmm = B.fit_hmm(comp, gold, sentinel, cfg)
    frozen = receipt["baselines"]
    duration_digest = _frozen_digest(dict(work=duration.work, scene=duration.scene))
    lopo_digest = _frozen_digest(dict(work=lopo.work, scene=lopo.scene))
    if duration_digest != frozen["duration_prior_sha256"]:
        raise SealedPreflightError(
            "development duration prior differs from its frozen digest"
        )
    if lopo_digest != frozen["lopo_marginal_sha256"]:
        raise SealedPreflightError(
            "development LOPO marginal differs from its frozen digest"
        )
    for actual, expected, name in (
        (nonfilm_rate, frozen["lopo_nonfilm_rate"], "lopo_nonfilm_rate"),
        (gold_context, frozen["gold_context_fitted"], "gold_context_fitted"),
        (hmm, frozen["hmm_rates"], "hmm_rates"),
    ):
        if actual != expected:
            raise SealedPreflightError(f"development fit differs from frozen {name}")
    if _frozen_digest(gold_context) != frozen["gold_context_fitted_sha256"]:
        raise SealedPreflightError(
            "gold-context fitted digest differs from its frozen digest"
        )
    if _frozen_digest(hmm) != frozen["hmm_rates_sha256"]:
        raise SealedPreflightError("HMM rates differ from their frozen digest")
    return FrozenTraining(
        cfg,
        comp,
        gold,
        B.scene_tokens(comp, adapter.producer_unit_texts(), cfg),
        duration,
        lopo,
        nonfilm_rate,
        gold_context,
        hmm,
        receipt,
    )


def frozen_baseline_forecasts(adapter, gold: dict, training: FrozenTraining) -> dict:
    """Forecast sealed rows from frozen development fits; no sealed row enters a fit."""
    out = {name: {} for name in REQUIRED_BASELINES}
    out["gold_context_ceiling"] = {}
    duration = B.with_nonfilm(training.duration, training.nonfilm_rate)
    for participant, rows in gold.items():
        for session, session_rows in R.by_session(rows).items():
            key = (participant, session)
            words = adapter.session_words(participant, session)
            tokens = [
                [
                    t
                    for word in words
                    if bin_.contains(word.onset)
                    for t in B.tokens(word.text)
                ]
                for bin_ in R.bins_of(session_rows)
            ]
            out["duration"][key] = [duration] * len(session_rows)
            # LOPO's work/scene marginal is frozen separately; its film-level probability is
            # the frozen all-development non-film rate, never the config's generic fallback.
            out["lopo"][key] = [
                B.with_nonfilm(training.lopo, training.nonfilm_rate)
            ] * len(session_rows)
            out["gold_context_ceiling"][key] = B.order_oracle(
                training.comp, session_rows, training.cfg, duration
            )
            out["gold_context_fitted"][key] = B.gold_context_fitted(
                training.comp,
                session_rows,
                training.cfg,
                duration,
                training.gold_context,
            )
            out["tfidf"][key] = B.tfidf(
                training.comp, tokens, training.docs, training.cfg, duration
            )
            out["hmm_tfidf"][key] = B.hmm_tfidf(
                training.comp,
                tokens,
                training.docs,
                training.cfg,
                duration,
                training.hmm,
            )
    return out


def account_forecasts(gold: dict, account, training: FrozenTraining) -> dict:
    """Project one producer account to the registered fallback rule for the given row grain."""
    rules = training.cfg["rules"]
    duration = B.with_nonfilm(training.duration, training.nonfilm_rate)
    out = {}
    for participant, rows in gold.items():
        for session, session_rows in R.by_session(rows).items():
            key = (participant, session)
            ld = LocationDistribution.from_words(
                account,
                account.words[key],
                R.bins_of(session_rows),
                training.cfg["allocation"],
                rules["cross_scene_broad"],
            )
            mixes = R.row_frame_mixes(account, key, session_rows)
            fallbacks = [
                B.frame_fallback(duration, mix, training.cfg["frame_floor"])
                for mix in mixes
            ]
            out[key] = SC.producer_forecasts(ld, duration, training.cfg, fallbacks)
    return out


def _keys(gold: dict) -> set:
    return {(p, g.session) for p, rows in gold.items() for g in rows}


def _score(
    gold: dict, forecasts: dict, comp, valid: dict, work_applicable: bool
) -> list:
    expected = _keys(gold)
    if set(forecasts) != expected:
        raise SealedPreflightError(
            "forecast keys must equal the scored gold participant/session keys"
        )
    out = []
    for p, rows in gold.items():
        by_session = {}
        for row in rows:
            by_session.setdefault(row.session, []).append(row)
        for session, session_rows in by_session.items():
            out.extend(
                SC.score_rows(
                    session_rows, forecasts[(p, session)], comp, valid, work_applicable
                )
            )
    return out


def ensemble_forecasts(sample_one: dict, sample_two: dict) -> dict:
    """Average two distributions per row, never their already-computed losses."""
    if set(sample_one) != set(sample_two):
        raise SealedPreflightError(
            "the two samples must cover identical participant/session keys"
        )
    out = {}
    for key in sample_one:
        left, right = sample_one[key], sample_two[key]
        if len(left) != len(right):
            raise SealedPreflightError(
                f"two samples have different row counts for {key}"
            )
        out[key] = [SC.EnsembleForecast([a, b]) for a, b in zip(left, right)]
    return out


def participant_gains(producer_scores: list, baseline_scores: dict) -> dict:
    """Per-participant primary gains against the lower-loss frozen TF-IDF/HMM baseline."""
    if not set(REQUIRED_BASELINES).issubset(baseline_scores):
        raise SealedPreflightError("all frozen baseline score sets are required")
    keyed = lambda rows: {(x.participant, x.session, x.row): x for x in rows}
    prod, tfidf, hmm = (
        keyed(producer_scores),
        keyed(baseline_scores["tfidf"]),
        keyed(baseline_scores["hmm_tfidf"]),
    )
    if set(prod) != set(tfidf) or set(prod) != set(hmm):
        raise SealedPreflightError(
            "producer, TF-IDF, and HMM rows must have identical identities"
        )
    values = {p: [] for p in SCORABLE_PARTICIPANTS}
    for key, score in prod.items():
        if key[0] not in values or score.scene_bits is None:
            continue
        a, b = tfidf[key].scene_bits, hmm[key].scene_bits
        if a is None or b is None:
            raise SealedPreflightError(
                "every producer scene row requires both frozen baseline scene losses"
            )
        values[key[0]].append((a - score.scene_bits, b - score.scene_bits))
    if any(not values[p] for p in SCORABLE_PARTICIPANTS):
        raise SealedPreflightError(
            "each coded sealed participant needs at least one specific scene row"
        )
    return {
        p: min(math.fsum(losses) / len(rows) for losses in zip(*rows))
        for p, rows in values.items()
    }


def primary_decision(gains: dict, window_gains: dict) -> dict:
    """Apply exactly: 5/5 positive primary gains, mean >= 0.5 bits, and >=4/5 window wins."""
    expected = set(SCORABLE_PARTICIPANTS)
    if set(gains) != expected or set(window_gains) != expected:
        raise SealedPreflightError(
            "both comparisons must cover exactly the five scorable participants"
        )
    primary = {p: float(gains[p]) for p in SCORABLE_PARTICIPANTS}
    windows = {p: float(window_gains[p]) for p in SCORABLE_PARTICIPANTS}
    if not all(math.isfinite(x) for x in (*primary.values(), *windows.values())):
        raise SealedPreflightError("gains must be finite")
    n = len(SCORABLE_PARTICIPANTS)
    wins, window_wins = (
        sum(x > 0 for x in primary.values()),
        sum(x > 0 for x in windows.values()),
    )
    mean = math.fsum(primary.values()) / n
    p_value = sign_flip_p(list(primary.values()), "greater")
    return dict(
        schema="storymodel4s.case-study-2.sealed-primary/v1",
        n_sealed_sessions=len(SEALED_SESSIONS),
        n_scorable_participants=n,
        participant_gains_bits=primary,
        fixed_window_gains_bits=windows,
        primary_wins=wins,
        mean_gain_bits=mean,
        one_sided_exact_sign_flip_p=p_value,
        fixed_window_wins=window_wins,
        passed=wins == n
        and p_value <= 1 / 32
        and mean >= MEAN_GAIN_BITS
        and window_wins >= n - 1,
        rule="5/5 primary wins, mean gain >= 0.5 bits, and >=4/5 fixed-window wins",
    )


def evaluate(
    gold: dict,
    sample_one: dict,
    sample_two: dict,
    frozen_baselines: dict,
    window_gold: dict,
    window_sample_one: dict,
    window_sample_two: dict,
    window_baselines: dict,
    comp,
    valid: dict,
    work_applicable: bool = True,
) -> dict:
    """Score both declared grains using caller-authorized inputs and immutable baseline maps."""
    ensemble = ensemble_forecasts(sample_one, sample_two)
    window_ensemble = ensemble_forecasts(window_sample_one, window_sample_two)
    producer = _score(gold, ensemble, comp, valid, work_applicable)
    baselines = {
        name: _score(gold, fmap, comp, valid, work_applicable)
        for name, fmap in frozen_baselines.items()
    }
    window_producer = _score(window_gold, window_ensemble, comp, valid, work_applicable)
    window_scores = {
        name: _score(window_gold, fmap, comp, valid, work_applicable)
        for name, fmap in window_baselines.items()
    }
    gains, windows = (
        participant_gains(producer, baselines),
        participant_gains(window_producer, window_scores),
    )
    result = primary_decision(gains, windows)
    result["ensemble"] = "probabilities averaged per row before scoring"
    return result


def evaluate_accounts(
    adapter, gold: dict, acc1, acc2, controls: dict, training: FrozenTraining
) -> dict:
    """Evaluate two sealed producer accounts with frozen baselines at gold spans and 7 s windows.

    ``adapter`` is the parent-owned registered adapter.  This function neither changes its
    authority nor invokes it beyond the words needed for caller-provided gold rows.
    """
    valid = B.valid_scenes(training.comp, training.cfg)
    window_gold = R.windowed_gold(adapter, gold, 7.0)
    base = frozen_baseline_forecasts(adapter, gold, training)
    window_base = frozen_baseline_forecasts(adapter, window_gold, training)
    sample1, sample2 = (
        account_forecasts(gold, acc1, training),
        account_forecasts(gold, acc2, training),
    )
    window1, window2 = (
        account_forecasts(window_gold, acc1, training),
        account_forecasts(window_gold, acc2, training),
    )
    result = evaluate(
        gold,
        sample1,
        sample2,
        base,
        window_gold,
        window1,
        window2,
        window_base,
        training.comp,
        valid,
    )
    s1, s2 = (
        _score(gold, sample1, training.comp, valid, True),
        _score(gold, sample2, training.comp, valid, True),
    )
    ensemble_scores = _score(
        gold, ensemble_forecasts(sample1, sample2), training.comp, valid, True
    )
    baseline_scores = {
        name: _score(gold, fmap, training.comp, valid, True)
        for name, fmap in base.items()
    }
    p1, p2 = SC._pmeans(s1, "scene_bits"), SC._pmeans(s2, "scene_bits")
    deltas = [p2[p] - p1[p] for p in SCORABLE_PARTICIPANTS]
    mean_delta = math.fsum(deltas) / len(deltas)
    noise_band = (
        2
        * math.sqrt(
            math.fsum((d - mean_delta) ** 2 for d in deltas) / (len(deltas) - 1)
        )
        / math.sqrt(len(deltas))
    )
    result["noise_band_scene_bits"] = noise_band
    duration = B.with_nonfilm(training.duration, training.nonfilm_rate)
    null = R.abstain_check(
        adapter, training.comp, gold, training.cfg, dict.fromkeys(gold, duration), True
    )
    null["passed"] = null["max_abs_bits_difference"] == 0
    result["controls"] = {"null": null}
    if set(controls) != {"content-shuffle", "wrong-context"}:
        raise SealedPreflightError("both registered controls are required")
    producer_mean = SC.summarize(ensemble_scores, training.cfg, "scene_bits")["mean"]
    hmm_mean = SC.summarize(baseline_scores["hmm_tfidf"], training.cfg, "scene_bits")[
        "mean"
    ]
    for name, account in controls.items():
        scores = _score(
            gold, account_forecasts(gold, account, training), training.comp, valid, True
        )
        summary = SC.summarize(scores, training.cfg, "scene_bits")
        if name == "content-shuffle":
            threshold = (producer_mean + hmm_mean) / 2
            decision = dict(threshold=threshold, passed=summary["mean"] >= threshold)
        else:
            delta = summary["mean"] - producer_mean
            decision = dict(delta=delta, band=noise_band, passed=delta >= -noise_band)
        result["controls"][name] = dict(gold_span_scene_bits=summary, **decision)
    return result
