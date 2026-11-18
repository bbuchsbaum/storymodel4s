"""Production helpers for the registered six-session FilmFestival run."""
import hashlib
import json
import math
import os
import random
import shutil
import subprocess
from pathlib import Path

from adapters import filmfestival
from cs2 import baselines, ff_producer, scorer
from cs2.sealed import SEALED_SESSIONS

MODEL = "claude-opus-5-5"
SESSIONS = tuple(tuple(key.split(":")) for key in SEALED_SESSIONS)
STUDY = filmfestival.DATA / "study" / "recall-precision" / "cs2"
REGISTERED_OUT = STUDY / "sealed-20261117"
SEGMENTER = "61c3664eed9a37caf7f83a5ea9728d69c2d68371"
CONFIG_SHA = "59f3b1708fedd6a7cfa48aec784a5fea65f82e03502057698c8987ed2c72af08"


class RegisteredFilmFestival:
    """Explicit study-specific access to the six approved sessions."""

    WORD_CLOCK_RECEIPT = filmfestival.WORD_CLOCK_RECEIPT
    producer_composition = staticmethod(filmfestival.producer_composition)
    producer_unit_texts = staticmethod(filmfestival.producer_unit_texts)

    @staticmethod
    def session_words(p, s):
        if (p, s) not in SESSIONS:
            raise ValueError("outside the registered sessions")
        return filmfestival.session_words(p, s, allow_frozen=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_inputs():
    receipt_path = STUDY / "prereg-20261117/frozen-digests.json"
    if (
        digest(receipt_path)
        != "bcfa6a16bce7aaa86f738a6713296e55d8228dc90211a8833bd5c8b459d89053"
    ):
        raise ValueError("original registration receipt changed")
    receipt = json.loads(receipt_path.read_text())
    if digest(scorer.CONFIG) != CONFIG_SHA:
        raise ValueError("scorer configuration differs from the tie amendment")
    systems = (ff_producer.PASS1_SYSTEM, ff_producer.PASS2_SPAN_ARMS["coverage"])
    conditions = ff_producer.conditions(
        20261201, "cs2-ff-sealed-s1", 1, systems=systems, layout="prefix"
    )
    for name, condition in conditions.items():
        for field, expected in receipt[name].items():
            if condition[field] != expected:
                raise ValueError(f"frozen digest mismatch: {name}.{field}")
    if set(filmfestival._partition()["frozen"]) != {p for p, s in SESSIONS}:
        raise ValueError("FilmFestival partition differs from the registered six")
    seg = STUDY / "filmfestival/seg"
    receipt = json.loads((seg / "segmentation-run.json").read_text())
    for name in ("source/annotations.csv", "source/parts.json"):
        if digest(seg / name) != receipt["digests"][name]:
            raise ValueError(f"development segmentation source changed: {name}")
    clone = filmfestival.DATA.parent / ".worktrees/cs2-seg-clone"

    def git(*args):
        return subprocess.check_output(
            ["git", "-C", str(clone), *args], text=True
        ).strip()

    if git("rev-parse", "HEAD") != SEGMENTER or git(
        "status", "--porcelain", "--untracked-files=normal"
    ):
        raise ValueError("segmenter checkout has changed")
    if shutil.which("sbt") is None or shutil.disk_usage(seg).free < 3 * 1024**3:
        raise ValueError("segmenter needs sbt and 3 GiB free")
    return seg, clone


def prepare_units(out, seg, clone):
    manifest_dir = out / "transcripts"
    manifest_dir.mkdir()
    manifest = dict(
        schema="storymodel4s.agentmapping.transcript-study/v1",
        model="segmentation-only-no-calls",
        condition="cs2-library-segmenter",
        chunkSize=50,
        sessions=[],
    )
    for p, s in SESSIONS:
        if filmfestival.sessions(p, allow_frozen=True) != [s]:
            raise ValueError("sealed session roster changed")
        name = f"{p}_{s}.txt"
        words = RegisteredFilmFestival.session_words(p, s)
        (manifest_dir / name).write_text(ff_producer.transcript_of(words))
        manifest["sessions"].append(dict(participant=p, session=s, transcript=name))
    path = manifest_dir / "manifest.json"
    path.write_text(json.dumps(manifest))
    export = out / "export"
    args = [seg / "source/annotations.csv", seg / "source/parts.json", path, export]
    command = (
        "pipeline/runMain storymodel4s.pipeline.agentmapping.agentStudyPrepare "
        + " ".join(f'"{arg}"' for arg in args)
    )
    with (out / "segmenter.log").open("x") as log:
        subprocess.run(
            ["sbt", "-batch", command],
            cwd=clone,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=True,
        )
    sessions, _ = ff_producer.load_export(export)
    if set(sessions) != set(SESSIONS):
        raise ValueError("library export does not contain the six registered sessions")
    return export


def produce(out, export, sample, rules, control=None):
    salt = f"cs2-ff-sealed-s{sample}"
    name = control or f"sample-{sample}"
    acc, ledger, calls, conditions = ff_producer.run(
        RegisteredFilmFestival,
        SESSIONS,
        export,
        out / name,
        20261200 + sample,
        salt,
        sample,
        rules=rules,
        layout="prefix",
        control=control,
        fresh_model=MODEL,
        pass1_from=out / "sample-1" if control else None,
    )
    if not calls or any(not c["fresh"] for c in calls):
        raise ValueError("sealed producer lacks a fresh call receipt")
    (out / name / "ledger.json").write_text(json.dumps(ledger, indent=1))
    return acc


def memorization(gold, comp, valid):
    """Same two identifier-only batches and chance rule as the development control."""
    rows = [g for p in sorted(gold) for g in gold[p] if g.specific]
    random.Random("cs2-stage3-memo").shuffle(rows)
    rows = rows[:80]
    if len(rows) != 80:
        raise ValueError("memorization probe requires eighty specific rows")
    code_of = {w: c for c, w in filmfestival._work_codes(comp).items()}
    system = "You answer questions about a published research dataset from memory. Reply with JSON only."
    log, answers = [], {}

    def row_id(g):
        return f"{g.participant}/{g.session}/row-{g.row}"

    for i in range(2):
        ids = [row_id(g) for g in rows[40 * i : 40 * (i + 1)]]
        prompt = (
            "Dataset: the FilmFestival study (Lee & Chen; OpenNeuro ds004042), whose recall "
            "transcripts were coded utterance by utterance with a movie code (1-10, in viewing "
            "order) and scene numbers (1-216) in recall_scenematched. For each coded utterance "
            "below, given only its participant, recall session and row number, give the movie "
            "code and one scene number you believe it was coded with. Guess if unsure.\n\n"
            + "\n".join(ids)
            + '\n\nReply with JSON only: {"rows": [{"id": "...", "movie": 1, '
            '"scene": 1}]} covering every row.'
        )
        got, _ = ff_producer.parse_with_retry(
            prompt,
            system,
            log,
            f"memo:call{i}",
            "rows",
            "cs2-memorization-probe",
            fresh_model=MODEL,
            expected_ids=set(ids),
        )
        for answer in got or []:
            if (
                isinstance(answer, dict)
                and answer.get("id") in ids
                and isinstance(answer.get("movie"), int)
                and not isinstance(answer["movie"], bool)
                and 1 <= answer["movie"] <= 10
                and isinstance(answer.get("scene"), int)
                and not isinstance(answer["scene"], bool)
                and 1 <= answer["scene"] <= 216
            ):
                answers[answer["id"]] = answer
    n = len(rows)

    def measure(hits, chance):
        rate = hits / n
        return dict(
            accuracy=rate if len(answers) == n else None,
            chance=chance,
            passed=rate <= chance + 2 * math.sqrt(chance * (1 - chance) / n)
            if len(answers) == n
            else None,
        )

    movie = sum(
        answers.get(row_id(g), {}).get("movie") == code_of[g.work] for g in rows
    )
    scene = sum(answers.get(row_id(g), {}).get("scene") in g.scenes for g in rows)
    return dict(
        complete=len(answers) == n,
        answered=len(answers),
        n=n,
        calls=log,
        movie=measure(movie, 0.1),
        scene=measure(scene, sum(1 / len(valid[g.work]) for g in rows) / n),
    )


def development_dry_run(seg):
    import tempfile
    from cs2 import run
    from cs2.test_cs2 import test_frozen_participants_refused

    test_frozen_participants_refused()
    p = filmfestival.development()[0]
    session = filmfestival.sessions(p)[0]
    with tempfile.TemporaryDirectory() as tmp:
        _, _, calls, conditions = ff_producer.run(
            filmfestival,
            [(p, session)],
            seg / "export",
            Path(tmp),
            20261201,
            "cs2-ff-sealed-s1",
            1,
            dry=True,
            layout="prefix",
            rules=run.resolve_rules(scorer.load_config()),
        )
    if calls:
        raise ValueError("development dry run made a model call")
    expected = json.loads((STUDY / "prereg-20261117/frozen-digests.json").read_text())
    for name, condition in conditions.items():
        if any(condition[k] != v for k, v in expected[name].items()):
            raise ValueError(f"development dry run failed to reproduce {name}")


def provider_probe():
    """Check the subscription using public synthetic text before any held-out read."""
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}
        completed = subprocess.run(
            [
                "claude",
                "-p",
                "--model",
                "opus",
                "--tools",
                "",
                "--system-prompt",
                "Reply with OK.",
                "--no-session-persistence",
                "--setting-sources",
                "",
                "--strict-mcp-config",
                "--output-format",
                "json",
                "Synthetic availability check",
            ],
            env=env,
            cwd=tmp,
            capture_output=True,
            text=True,
            timeout=120,
        )
    response = json.loads(completed.stdout)
    if completed.returncode or response.get("is_error"):
        raise RuntimeError(
            f"synthetic subscription check failed: {str(response.get('result'))[:240]}"
        )
    if set(response.get("modelUsage", {})) != {MODEL}:
        raise RuntimeError("synthetic probe did not resolve to the registered model")


def secondaries(adapter, gold, accounts, training):
    """Participant means, cluster intervals and sign-flip comparisons for section 3."""
    from statistics import mean
    from cs2 import run, sealed
    from metrics import cluster_bootstrap, sign_flip_p

    comp, cfg = training.comp, training.cfg
    valid = baselines.valid_scenes(comp, cfg)
    duration = baselines.with_nonfilm(training.duration, training.nonfilm_rate)
    samples = [sealed.account_forecasts(gold, acc, training) for acc in accounts]
    ensemble = sealed.ensemble_forecasts(*samples)
    base = sealed.frozen_baseline_forecasts(adapter, gold, training)
    by_measure = {}

    for p, rows in gold.items():
        current, frames, others = [], [], {name: [] for name in base}
        for session, gs in run.by_session(rows).items():
            key = p, session

            def score(fs):
                return scorer.score_rows(gs, fs, comp, valid)

            rs = score(ensemble[key])
            for row, a, b in zip(rs, samples[0][key], samples[1][key], strict=True):
                claimed = 2 - a.abstain - b.abstain
                if claimed > 0:
                    row.located_share = (
                        sum(a.loc.values()) + sum(b.loc.values())
                    ) / claimed
            current.extend(rs)
            paired_frames = []
            for account in accounts:
                mixes = run.row_frame_mixes(account, key, gs)
                paired_frames.append(
                    [
                        baselines.frame_fallback(duration, mix, cfg["frame_floor"])
                        for mix in mixes
                    ]
                )
            frames.extend(
                score(
                    [
                        scorer.EnsembleForecast(pair)
                        for pair in zip(*paired_frames, strict=True)
                    ]
                )
            )
            for name, mapping in base.items():
                others[name].extend(score(mapping[key]))

        def term(scores, name):
            values = [getattr(r, name) for r in scores if getattr(r, name) is not None]
            return mean(values) if values else math.nan

        def record(name, value, reference):
            if math.isfinite(value) and math.isfinite(reference):
                by_measure.setdefault(name, {})[p] = (value, value - reference)

        record(
            "work_bits_vs_pass1", term(current, "work_bits"), term(frames, "work_bits")
        )
        record(
            "film_bits_vs_lopo",
            term(current, "film_bits"),
            term(others["lopo"], "film_bits"),
        )
        for name in ("gold_context_ceiling", "gold_context_fitted"):
            for metric in ("scene_bits", "work_bits"):
                record(
                    f"{metric}_vs_{name}",
                    term(current, metric),
                    term(others[name], metric),
                )
        current_strata = scorer.strata(current, comp)
        frame_switches = scorer.strata(frames, comp)["film_switch_f1"]
        for metric in ("f1", "f1_tolerance_1_row"):
            record(
                f"switch_{metric}_vs_pass1",
                current_strata["film_switch_f1"][metric],
                frame_switches[metric],
            )
        record("top_scene_vs_0.80", term(current, "scene_hit"), 0.80)
        record(
            "localizability_auroc_vs_0.80",
            current_strata["localizability"]["auroc"],
            0.80,
        )
    bootstrap = cfg["bootstrap"]
    out = {}
    for name, paired in by_measure.items():
        values = {p: v for p, (v, _) in paired.items()}
        diffs = {p: d for p, (_, d) in paired.items()}

        def interval(series):
            return cluster_bootstrap(series, mean, **bootstrap)

        out[name] = dict(
            n=len(paired),
            participant_means=values,
            mean=mean(values.values()),
            ci=interval(values),
            difference=mean(diffs.values()),
            difference_ci=interval(diffs),
            p_two_sided=sign_flip_p(list(diffs.values()), "two-sided"),
        )
    return out


def main():
    import argparse
    from cs2 import run, sealed

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("preflight", "run"))
    args = parser.parse_args()
    seg, clone = verify_inputs()
    development_dry_run(seg)
    training = sealed.freeze_training(filmfestival)
    if args.mode == "preflight":
        print("Development preflight passed. No provider calls or held-out reads.")
        return
    root = Path(__file__).resolve().parents[4]
    registration = root / "docs/plans/2026-11-17-cs2-preregistration.md"
    import re

    registered = re.search(
        r"Executable freeze commit: `([0-9a-f]{40})`", registration.read_text()
    )
    if registered is None:
        raise RuntimeError("no executable freeze recorded in registration")
    dirty = subprocess.check_output(
        ["git", "status", "--porcelain"], cwd=root, text=True
    )
    if dirty.strip():
        raise RuntimeError("sealed run requires a clean checkout")
    target = registered[1]
    subprocess.run(
        ["git", "merge-base", "--is-ancestor", target, "HEAD"], cwd=root, check=True
    )
    subprocess.run(
        ["git", "diff", "--exit-code", "--quiet", target, "HEAD", "--", "tools"],
        cwd=root,
        check=True,
    )
    if REGISTERED_OUT.exists():
        raise RuntimeError("sealed run already attempted: consult its manifest")
    provider_probe()
    os.umask(0o077)
    REGISTERED_OUT.mkdir(mode=0o700)
    out = REGISTERED_OUT
    state = dict(
        frozen_commit=target,
        **run.git_state(),
        state="started",
        scorer_sha=CONFIG_SHA,
        segmenter_commit=SEGMENTER,
        expected_model=MODEL,
    )
    manifest = out / "manifest.json"
    manifest.write_text(json.dumps(state, indent=1))
    try:
        export = prepare_units(out, seg, clone)
        rules = run.resolve_rules(scorer.load_config())
        sessions = [produce(out, export, k, rules) for k in (1, 2)]
        controls = {
            name: produce(out, export, 1, rules, control=name)
            for name in ("content-shuffle", "wrong-context")
        }
        gold = {p: filmfestival.gold_rows(p, s, allow_frozen=True) for p, s in SESSIONS}
        gold = {k: v for k, v in gold.items() if v}
        if set(gold) != set(sealed.SCORABLE_PARTICIPANTS):
            raise RuntimeError("scorable roster differs from the approved five")
        comp = filmfestival.producer_composition()
        memo = memorization(
            gold, comp, baselines.valid_scenes(comp, scorer.load_config())
        )
        result = sealed.evaluate_accounts(
            RegisteredFilmFestival, gold, *sessions, controls, training
        )
        result["secondary"] = secondaries(
            RegisteredFilmFestival, gold, sessions, training
        )
        result["memorization"] = memo
        (out / "results.json").write_text(json.dumps(result, indent=1))
    except BaseException as exc:
        state.update(state="interrupted", exception=type(exc).__name__)
        raise
    else:
        state["state"] = "completed"
    finally:
        manifest.write_text(json.dumps(state, indent=1))


if __name__ == "__main__":
    main()
