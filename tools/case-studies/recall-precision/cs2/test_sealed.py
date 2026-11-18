"""Synthetic tests for ``cs2.sealed``; no participant data or providers are opened."""

from __future__ import annotations

from cs2 import sealed
from cs2.scorer import RowScore


def test_primary_rule_uses_five_coded_participants_and_all_three_gates():
    gains = dict.fromkeys(sealed.SCORABLE_PARTICIPANTS, 0.5)
    windows = dict.fromkeys(sealed.SCORABLE_PARTICIPANTS, 0.1)
    result = sealed.primary_decision(gains, windows)
    assert (
        result["passed"]
        and result["primary_wins"] == 5
        and result["fixed_window_wins"] == 5
    )
    assert result["one_sided_exact_sign_flip_p"] == 1 / 32
    gains["sub-20"] = 0.0
    assert not sealed.primary_decision(gains, windows)["passed"]
    gains["sub-20"] = 0.5
    windows["sub-20"] = windows["sub-13"] = 0.0
    assert not sealed.primary_decision(gains, windows)["passed"]


def test_reported_p_value_can_block_a_numerically_tied_fifth_win():
    gains = dict.fromkeys(sealed.SCORABLE_PARTICIPANTS, 1.0)
    gains["sub-20"] = 1e-14
    result = sealed.primary_decision(gains, dict.fromkeys(gains, 1.0))
    assert result["one_sided_exact_sign_flip_p"] > 1 / 32
    assert not result["passed"]


def test_primary_rule_refuses_to_score_unscorable_sixth_participant():
    gains = dict.fromkeys(sealed.SCORABLE_PARTICIPANTS, 1.0)
    gains["sub-06"] = 1.0
    try:
        sealed.primary_decision(gains, dict(gains))
    except sealed.SealedPreflightError:
        return
    raise AssertionError("sub-06 was silently added to the primary denominator")


def test_freeze_training_reproduces_the_actual_development_receipt():
    import data as D

    training = sealed.freeze_training(D.adapter("filmfestival"))
    frozen = training.receipt["baselines"]
    assert training.nonfilm_rate == frozen["lopo_nonfilm_rate"]
    assert training.gold_context == frozen["gold_context_fitted"]
    assert training.hmm == frozen["hmm_rates"]


def test_participant_gains_uses_the_better_frozen_baseline_per_participant():
    def rows(values):
        return [
            RowScore(p, "run-01", i, "specific", "film", None, v)
            for p in sealed.SCORABLE_PARTICIPANTS
            for i, v in enumerate(values)
        ]

    # Different baselines win individual rows. Choosing one per participant yields +0.5;
    # choosing the better one anew on each row would yield -1.
    producer = rows([2.0, 2.0])
    baselines = {name: rows([1.0, 5.0]) for name in sealed.REQUIRED_BASELINES}
    baselines["hmm_tfidf"] = rows([4.0, 1.0])
    assert sealed.participant_gains(producer, baselines) == dict.fromkeys(
        sealed.SCORABLE_PARTICIPANTS, 0.5
    )


def main():
    tests = [v for k, v in globals().items() if k.startswith("test_") and callable(v)]
    for test in tests:
        test()
    print(f"passed {len(tests)} failed 0")


if __name__ == "__main__":
    main()
