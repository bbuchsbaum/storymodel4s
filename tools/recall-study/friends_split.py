#!/usr/bin/env python3
"""Draw and seal the Friends participant-level test split (bd-01M2TA4QG638M6ZH8Q23CAK401).

Why: Friends is the first corpus the mapper has never been run on. It stays useful as a final test
only if its membership is fixed, committed and guarded before any model output exists. Membership is
drawn from the admitted participant IDs and a committed seed alone; no recall is read to draw it.
Sealing reads counts through friends_guard. Checking an existing seal reads metadata only.

    python3 tools/recall-study/friends_split.py --write   # once; refuses if the split exists
    python3 tools/recall-study/friends_split.py --check   # re-derive and compare; exit 1 on drift

The power block is computed here with the standard library (noncentral t by quadrature), so the
recorded minimum detectable effects are derived from committed inputs, not typed in.
"""

import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path
import platform
import random
import statistics
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import friends_guard as guard  # noqa: E402

REPO = guard.ss.REPO
SEED = 20260921
N_TEST = 10
RULE = (
    "random.Random(SEED).shuffle(IDs sorted by numeric suffix); "
    "first 13 development, remaining 10 test"
)
ALPHA = 0.05
POWER = 0.80
SHERLOCK_LADDER = "docs/data/sherlock/navigation-ladder-20260905/results.json"
SHERLOCK_CONTRASTS = ("ladder-content", "chosen-shuffled-dev", "prior0-chosen-dev")
FILMFEST = (
    "docs/data/filmfestival/stabilization-20260904/historical-rescored-final.json"
)
FILMFEST_CONTRAST = "historical-lexical-minus-historical-onnx"
HILL_CLIMB = "docs/plans/2026-09-17-friends-hill-climb-and-normalization.md"


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def pool_ids():
    manifest = json.loads((REPO / guard.SOURCE_MANIFEST).read_text(encoding="utf-8"))
    return list(manifest["workbookAudit"]["recallScoring"]["participantSheetIds"])


def draw(ids, seed=SEED, n_test=N_TEST):
    """Membership from IDs and seed only. Sorted by numeric suffix so input order cannot matter."""
    ordered = sorted(ids, key=lambda s: int(s.lstrip("s")))
    random.Random(seed).shuffle(ordered)
    n_dev = len(ordered) - n_test
    return ordered[:n_dev], ordered[n_dev:]


# --- power, standard library only -------------------------------------------------------------


def _betacf(a, b, x):
    """Continued fraction for the regularized incomplete beta (modified Lentz)."""
    tiny, qab, qap, qam = 1e-300, a + b, a + 1.0, a - 1.0
    c, d = 1.0, 1.0 - qab * x / qap
    d = 1.0 / (d if abs(d) > tiny else tiny)
    h = d
    for m in range(1, 400):
        m2 = 2 * m
        for aa in (
            m * (b - m) * x / ((qam + m2) * (a + m2)),
            -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2)),
        ):
            d = 1.0 + aa * d
            d = 1.0 / (d if abs(d) > tiny else tiny)
            c = 1.0 + aa / c
            c = c if abs(c) > tiny else tiny
            h *= d * c
        if abs(d * c - 1.0) < 1e-15:
            return h
    raise ArithmeticError("incomplete beta did not converge")


def _betainc(a, b, x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    ln = (
        math.lgamma(a + b)
        - math.lgamma(a)
        - math.lgamma(b)
        + a * math.log(x)
        + b * math.log(1 - x)
    )
    if x < (a + 1.0) / (a + b + 2.0):
        return math.exp(ln) * _betacf(a, b, x) / a
    return 1.0 - math.exp(ln) * _betacf(b, a, 1.0 - x) / b


def t_cdf(t, df):
    tail = 0.5 * _betainc(df / 2.0, 0.5, df / (df + t * t))
    return 1.0 - tail if t > 0 else tail


def _bisect(f, lo, hi, target, iters=200):
    for _ in range(iters):
        mid = (lo + hi) / 2.0
        if f(mid) < target:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2.0


def t_ppf(p, df):
    return _bisect(lambda t: t_cdf(t, df), -60.0, 60.0, p)


def nct_cdf(t, df, ncp, steps=4000):
    """P(T <= t) for noncentral t: integrate Phi(t*sqrt(x/df) - ncp) over the chi-square(df) density."""
    upper = df + 40.0 * math.sqrt(2.0 * df)
    h = upper / steps
    lognorm = (df / 2.0) * math.log(2.0) + math.lgamma(df / 2.0)

    def integrand(x):
        if x <= 0.0:
            return 0.0
        dens = math.exp((df / 2.0 - 1.0) * math.log(x) - x / 2.0 - lognorm)
        return 0.5 * math.erfc(-(t * math.sqrt(x / df) - ncp) / math.sqrt(2.0)) * dens

    total = integrand(0.0) + integrand(upper)
    for i in range(1, steps):
        total += (4 if i % 2 else 2) * integrand(i * h)
    return total * h / 3.0


def paired_mde_effect_size(n, alpha=ALPHA, power=POWER):
    """Smallest standardized mean difference a two-sided one-sample t test detects with `power`."""
    df = n - 1
    crit = t_ppf(1.0 - alpha / 2.0, df)

    def achieved(ncp):
        return 1.0 - nct_cdf(crit, df, ncp) + nct_cdf(-crit, df, ncp)

    return _bisect(achieved, 0.0, 30.0, power) / math.sqrt(n), crit


def sd_assumptions(dev_median_units):
    """Paired per-participant difference SDs from committed studies, plus a Friends-scaled value."""
    ladder = json.loads((REPO / SHERLOCK_LADDER).read_text(encoding="utf-8"))
    rows = []
    for name in SHERLOCK_CONTRASTS:
        vals = list(
            ladder["contrastsVersusFull"][name]["sceneExact"][
                "perParticipantChangePP"
            ].values()
        )
        rows.append(
            {
                "label": f"Sherlock {name}, scene-exact, paired change",
                "sdPp": round(statistics.stdev(vals), 4),
                "participants": len(vals),
                "source": f"{SHERLOCK_LADDER}#contrastsVersusFull.{name}.sceneExact.perParticipantChangePP",
            }
        )
    ff = json.loads((REPO / FILMFEST).read_text(encoding="utf-8"))["comparisons"][
        FILMFEST_CONTRAST
    ]
    deltas = [p["deltaPp"] for p in ff["participants"]]
    ff_sd = statistics.stdev(deltas)
    ff_median_eligible = statistics.median(p["eligible"] for p in ff["participants"])
    rows.append(
        {
            "label": "FilmFestival lexical minus ONNX, paired change per eligible unit",
            "sdPp": round(ff_sd, 4),
            "participants": len(deltas),
            "source": f"{FILMFEST}#comparisons.{FILMFEST_CONTRAST}.participants[].deltaPp",
        }
    )
    rows.append(
        {
            "label": "FilmFestival SD rescaled to Friends unit counts (assumption)",
            "sdPp": round(ff_sd * math.sqrt(ff_median_eligible / dev_median_units), 4),
            "participants": None,
            "source": (
                f"FilmFestival SD x sqrt(median eligible units {ff_median_eligible} / Friends development "
                f"median gold units {dev_median_units}); assumes unit-sampling noise dominates"
            ),
        }
    )
    return rows


def power_block(dev_median_units):
    d, crit = paired_mde_effect_size(N_TEST)
    return {
        "estimand": "participant-average exact accuracy difference between two mappers (paired)",
        "method": (
            "two-sided one-sample t test on per-participant paired differences; noncentral t power "
            "by Simpson quadrature over the chi-square density (stdlib, this script)"
        ),
        "testParticipants": N_TEST,
        "alpha": ALPHA,
        "power": POWER,
        "criticalT": round(crit, 6),
        "mdeEffectSize": round(d, 6),
        "mdeBySdAssumption": [
            {**row, "mdePp": round(d * row["sdPp"], 2)}
            for row in sd_assumptions(dev_median_units)
        ],
        "caveats": [
            "Friends has a median of 23 gold units per participant against hundreds per FilmFestival "
            "participant, so its per-participant accuracy is noisier; the rescaled row is the "
            "assumption closest to Friends and the others are optimistic.",
            "The primary analysis may use a participant-cluster bootstrap rather than a t test; the "
            "t-based MDE is the planning figure, not the analysis.",
        ],
    }


EXPOSURE = {
    "status": "model-untouched, not unseen",
    "priorHumanCodingStatistics": [
        f"Aggregate recall-order statistics over all 23 participants: per-participant Spearman rho "
        f"median 0.39 (range -0.53 to 0.98, 4 of 23 negative), within storyline median 0.90 "
        f"(2 of 23 negative); 74.1% non-decreasing transitions, 81.5% within storyline, 38.2% "
        f"storyline switches ({HILL_CLIMB}:96-105).",
        f"Individual rho values named for s6, s26 and s21 ({HILL_CLIMB}:107-109).",
        f"Gold-unit counts and per-event coverage over all 23: 630 units, median 23 per participant, "
        f"range 4-46; per-event coverage median 11 of 23 ({HILL_CLIMB}:142-149).",
        "Per-sheet recall extents and code-book counts in docs/data/friends/source-manifest.json.",
    ],
    "modelOutputs": (
        "None at seal time. Searched data/, tmp/, docs/ and .worktrees/ for Friends-named outputs on "
        "2026-09-21 and found none; docs/plans/2026-09-18-best-recall-to-video-mapper.md:71 records "
        "Friends as never run."
    ),
    "rule": (
        "Test metadata used for this accounting must not enter tuning; nothing in Phases 1a-2 reads "
        "the test side (docs/plans/2026-09-18-best-recall-to-video-mapper.md:172-180)."
    ),
}


def build(data_root):
    guard._require_unsealed()
    ids = pool_ids()
    dev, test = draw(ids)
    split = {
        "schema": guard.FRIENDS.split_schema,
        "corpus": guard.FRIENDS.name,
        "pool": {"participants": sorted(ids, key=lambda s: int(s.lstrip("s")))},
        "development": {"participants": dev},
        "test": {"participants": test},
    }
    accounting = guard.seal_accounting(data_root, sealing_split=split)
    dev_counts = guard.development_unit_counts(data_root, sealing_split=split)
    return _record(split, accounting, statistics.median(dev_counts))


def _record(split, accounting, dev_median):
    dev, test = split["development"]["participants"], split["test"]["participants"]
    individually_exposed = [s for s in ("s6", "s26", "s21") if s in test]
    return {
        "schema": guard.FRIENDS.split_schema,
        "corpus": "friends",
        "mote": "bd-01M2TA4QG638M6ZH8Q23CAK401",
        "seed": SEED,
        "rule": RULE,
        "decision": (
            "Owner chose 10 test / 13 development on 2026-09-21 (alternatives offered: 12/11, 8/15, "
            f"hold). {HILL_CLIMB}:222-224 had suggested 14/9."
        ),
        "draw": {
            "script": "tools/recall-study/friends_split.py",
            "scriptSha256": sha256_file(HERE / "friends_split.py"),
            "guardSha256AtSeal": sha256_file(HERE / "friends_guard.py"),
            "python": platform.python_version(),
            "readsNoRecallToDraw": True,
        },
        "pool": {
            "participants": split["pool"]["participants"],
            "source": "docs/data/friends/source-manifest.json#workbookAudit.recallScoring.participantSheetIds",
        },
        "development": {
            "participants": dev,
            "count": len(dev),
            "goldUnits": accounting["totals"]["development"]["goldUnits"],
            "medianGoldUnitsPerParticipant": dev_median,
        },
        "test": {
            "participants": test,
            "count": len(test),
            "goldUnits": accounting["totals"]["test"]["goldUnits"],
            "individuallyExposedBeforeSeal": individually_exposed,
        },
        "goldUnitDefinition": (
            "Per participant sheet, a maximal run of consecutive rows whose WhichEvent, normalized to a "
            "number, is non-blank and equal and whose RecallType is 1; any other row ends the run. "
            "Veridical recall only. Reproduces the 630 units in "
            f"{HILL_CLIMB}:142."
        ),
        "sealAccounting": {
            "workbookSha256": accounting["workbookSha256"],
            "scope": (
                "friends_guard.seal_accounting: per-side participant and gold-unit totals, and the "
                "development median. No test participant's individual count is recorded."
            ),
        },
        "power": power_block(dev_median),
        "exposure": EXPOSURE,
        "guard": {
            "module": "tools/recall-study/friends_guard.py",
            "readLedger": "docs/data/friends/test-split-reads.json",
            "contract": (
                "require_readable refuses test participants unless given a FinalOpening naming a "
                "committed release-candidate manifest whose SHA-256 matches; each final opening is "
                "appended to the read ledger before access is granted."
            ),
        },
    }


def _comparable(record):
    """Fields that must reproduce exactly; the seal timestamp and python version are provenance."""
    out = json.loads(json.dumps(record))
    out.pop("sealedAtUtc", None)
    out["draw"].pop("python", None)
    out["draw"].pop("guardSha256AtSeal", None)
    out["draw"].pop(
        "scriptSha256", None
    )  # historical bytes at seal time, not current code
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    ap.add_argument("--data-root", default=str(REPO / "data"))
    args = ap.parse_args(argv)
    split_path, ledger_path = REPO / guard.FRIENDS.split, REPO / guard.FRIENDS.ledger
    if args.write:
        guard._require_unsealed()  # refuse before any workbook access
        if ledger_path.exists():
            raise guard.GuardRefusal("refusing to overwrite an existing read ledger")
        record = build(args.data_root)
        record["sealedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat(
            timespec="seconds"
        )
        with split_path.open("x", encoding="utf-8") as out:
            out.write(json.dumps(record, indent=2) + "\n")
        with ledger_path.open("x", encoding="utf-8") as out:
            out.write(json.dumps(guard.ss.empty_ledger(guard.FRIENDS), indent=2) + "\n")
        print(f"sealed {split_path}")
        return 0
    committed = guard.load_split()
    ids = pool_ids()
    dev, test = draw(ids, committed["seed"])
    split = {
        "pool": {"participants": sorted(ids, key=lambda s: int(s.lstrip("s")))},
        "development": {"participants": dev},
        "test": {"participants": test},
    }
    # The recorded counts are historical inputs. --check never remeasures recall.
    accounting = {
        "workbookSha256": committed["sealAccounting"]["workbookSha256"],
        "totals": {
            side: {"goldUnits": committed[side]["goldUnits"]}
            for side in ("development", "test")
        },
    }
    record = _record(
        split, accounting, committed["development"]["medianGoldUnitsPerParticipant"]
    )
    if _comparable(committed) != _comparable(record):
        print(
            "DRIFT: re-derived split record differs from the committed one",
            file=sys.stderr,
        )
        return 1
    print(f"{split_path}: membership and planning metadata current (no recall read)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
