#!/usr/bin/env python3
"""ADR 0027 section 4: the recorded model proposer for pronoun and description mentions.

Why three steps: only `execute` leaves the machine or spends money, and it runs once. `prepare`
fixes the requests and a freeze before any call, and `materialize` turns the recordings into
proposals offline, through the producer's deixis guards, so the run replays without the network.
The model only proposes. A deictic proposal it makes may still be refused by `guard_deixis`, and
then by the resolver; a candidate set becomes hints, which never resolve a link (ADR 0024).

The model reads the mention's decoder-segment words and the `Name - All` and `Name - Speaking`
entries of the overlapping and adjacent rows, never scene descriptions, proposals or outputs.

Usage:
  proposer.py prepare RUN_DIR TRANSCRIPT EXPERIMENT_DIR
  proposer.py execute EXPERIMENT_DIR        (needs OPENROUTER_API_KEY; spends money once)
  proposer.py materialize RUN_DIR EXPERIMENT_DIR OUTPUT_DIR
"""
from __future__ import annotations

import concurrent.futures
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import time
from typing import Callable
import urllib.error
import http.client
import urllib.request

# The sibling producer module, found even under `python3 -I`, which drops the script directory.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import producer  # noqa: E402

REQUESTS_SCHEMA = "source-identity-model-requests/v1"
FREEZE_SCHEMA = "source-identity-model-freeze/v1"
ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
# The dated pin of the earlier assessment rounds, on one provider, with no fallback or retention.
MODEL = "deepseek/deepseek-v4.1-flash-1017"
PROVIDER_NAME = "Fireworks"
PROMPT_PRICE, COMPLETION_PRICE = (
    0.30,
    1.20,
)  # USD per million tokens, enforced by routing
PROVIDER = dict(
    only=["fireworks"],
    allow_fallbacks=False,
    require_parameters=True,
    data_collection="deny",
    zdr=True,
    max_price=dict(prompt=PROMPT_PRICE, completion=COMPLETION_PRICE, request=0),
)
MAX_TOKENS = 8192  # this pin reasons at length, and reasoning counts toward the cap
BUDGET_CEILING_USD = 5.0  # owner decision 2 of ADR 0027
DECISIONS = ("deictic", "candidates", "absent-or-unclear")

PROMPT = """You read speech-recognition text from a film scene. One expression in it is marked
[[like this]]. It is a third-person pronoun or a noun describing a person. The input also lists the
people that annotators recorded in the nearby rows of the film, each with an id, and the listed
speaker of each row. "seconds" says when the expression is spoken, and rows with
"overlapsMention": true are the ones on screen at that moment. The recognition text may contain
errors.

Decide what the marked expression refers to, and answer with one JSON object and nothing else:
{"decision": D, "target": ID or null, "candidates": [IDs], "reason": "one short sentence"}

D is one of:
- "deictic": the marked expression is a pronoun that refers to exactly one listed person, who is
  present at that moment. Give that person's id as "target" and an empty "candidates" list. Do not
  use "deictic" when "class" is "noun".
- "candidates": it refers to a person compatible with one or more listed people, but you cannot
  single one out, or it is a description noun. List every compatible id; "target" is null.
- "absent-or-unclear": it refers to someone not listed, to no person, or you cannot tell. "target"
  is null and "candidates" is empty.

Use only ids that appear in the input's "candidates". Do not quote the dialogue in "reason".
"""


class Refusal(producer.Refusal):
    pass


def canonical(value) -> bytes:
    return json.dumps(
        value, sort_keys=True, ensure_ascii=False, separators=(",", ":")
    ).encode()


def sha(value) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def person_label(visual_id: str, names: dict) -> str:
    """A character's annotation name, or the role label an unnamed extra was listed under."""
    if visual_id in names:
        return names[visual_id]
    return "unnamed (" + visual_id.split("-", 2)[2] + ")"


# --- prepare -----------------------------------------------------------------------------------


def model_inputs(inputs: dict, universe: dict, transcript: dict) -> list[dict]:
    """One model input per mention in the frozen universe, from whitelisted facts only."""
    prod = inputs["producer"]
    rows = {r["row"]: r for r in prod["rows"]}
    meta = {m["id"]: m for m in prod["dialogue"]}
    names = prod["visualNames"]
    row_of = {v: r["row"] for r in prod["rows"] for v in r["visual"]}
    words = sorted(transcript["words"], key=lambda w: w["index"])
    out = []
    for mention in sorted({p["mention"] for p in universe["pairs"]}):
        m = meta[mention]
        if m["class"] not in ("pronoun", "noun"):
            raise Refusal(f"{mention} is not a pronoun or noun mention")
        marked = set(m["words"])
        text = " ".join(
            ("[[" + w["text"].strip() + "]]")
            if w["index"] in marked
            else w["text"].strip()
            for w in words
            if w["segment_index"] == m["segment"]
        )
        candidates = sorted(
            p["visual"] for p in universe["pairs"] if p["mention"] == mention
        )
        listed = sorted({row_of[v] for v in candidates})
        out.append(
            {
                "mention": mention,
                "class": m["class"],
                "seconds": [str(Decimal(t) / producer.TICKS) for t in m["span"]],
                "text": text,
                "rows": [
                    {
                        "row": n,
                        "seconds": [rows[n]["start"], rows[n]["end"]],
                        "people": [
                            {"id": v, "label": person_label(v, names)}
                            for v in rows[n]["visual"]
                        ],
                        "speaking": rows[n]["speaking"],
                        "overlapsMention": bool(producer.overlapping([rows[n]], m["span"])),
                    }
                    for n in listed
                ],
                "candidates": candidates,
            }
        )
    return out


def body(model_input: dict) -> dict:
    return dict(
        model=MODEL,
        temperature=0,
        max_tokens=MAX_TOKENS,
        reasoning=dict(effort="low", exclude=True),
        provider=PROVIDER,
        stream=False,
        messages=[
            dict(
                role="user",
                content=PROMPT
                + "\nINPUT:\n"
                + json.dumps(model_input, ensure_ascii=False, sort_keys=True),
            )
        ],
    )


def cost_bound(payload: dict) -> float:
    """Upper bound in USD: a byte count bounds prompt tokens, plus the whole completion budget,
    plus 256 tokens of chat framing. A per-request charge is refused by `max_price.request=0`."""
    prompt = sum(len(m["content"].encode()) for m in payload["messages"]) + 256
    return prompt * PROMPT_PRICE / 1e6 + payload["max_tokens"] * COMPLETION_PRICE / 1e6


def prepare(
    run_dir: Path, transcript_path: Path, experiment: Path, budget: float
) -> dict:
    """Fixes the requests and a freeze. Refuses an existing experiment."""
    if experiment.exists():
        raise Refusal("experiment already exists")
    if not (math.isfinite(budget) and 0 < budget <= BUDGET_CEILING_USD):
        raise Refusal(f"budget must be in (0, {BUDGET_CEILING_USD}]")
    inputs_path, universe_path = run_dir / "inputs.json", run_dir / "universe.json"
    inputs = json.loads(inputs_path.read_text("utf-8"))
    universe = json.loads(universe_path.read_text("utf-8"))
    transcript_sha = file_sha(transcript_path)
    pinned = {e["role"]: e["sha256"] for e in inputs["inputs"]}
    if pinned.get("asr-transcript") != transcript_sha:
        raise Refusal("the transcript is not the one the producer run pinned")
    requests = []
    for mi in model_inputs(
        inputs, universe, json.loads(transcript_path.read_text("utf-8"))
    ):
        b = body(mi)
        requests.append({"identity": sha(b), "mention": mi["mention"], "input": mi})
    bound = sum(cost_bound(body(r["input"])) for r in requests)
    if bound > budget:
        raise Refusal(f"cost bound {bound:.4f} exceeds budget {budget}")
    experiment.mkdir(parents=True)
    req_doc = {"schemaVersion": REQUESTS_SCHEMA, "requests": requests}
    (experiment / "requests.json").write_bytes(canonical(req_doc) + b"\n")
    freeze = {
        "schemaVersion": FREEZE_SCHEMA,
        "model": MODEL,
        "provider": PROVIDER,
        "harnessSha256": file_sha(Path(__file__)),
        "producerSha256": file_sha(Path(producer.__file__)),
        "requestsSha256": file_sha(experiment / "requests.json"),
        "inputsSha256": file_sha(inputs_path),
        "universeSha256": file_sha(universe_path),
        "budgetUsd": budget,
        "costBoundUsd": bound,
        "attemptsPerRequest": 1,
    }
    (experiment / "freeze.json").write_bytes(canonical(freeze) + b"\n")
    return freeze


# --- execute -----------------------------------------------------------------------------------


def validate(model_input: dict, raw: str) -> dict:
    """The proposal the model returned, or ValueError. Ids must come from the input's candidates."""
    p = json.loads(raw)
    if not isinstance(p, dict) or set(p) != {
        "decision",
        "target",
        "candidates",
        "reason",
    }:
        raise ValueError("wrong fields")
    allowed = set(model_input["candidates"])
    d, target, cands, reason = p["decision"], p["target"], p["candidates"], p["reason"]
    if d not in DECISIONS or not isinstance(reason, str) or not isinstance(cands, list):
        raise ValueError("wrong types")
    if d == "deictic":
        if target not in allowed or cands:
            raise ValueError(
                "a deictic proposal needs one candidate target and no list"
            )
    elif d == "candidates":
        if (
            target is not None
            or not cands
            or not set(cands) <= allowed
            or len(set(cands)) != len(cands)
        ):
            raise ValueError(
                "a candidate set needs distinct candidate ids and no target"
            )
    elif target is not None or cands:
        raise ValueError("absent-or-unclear carries no ids")
    return {
        "decision": d,
        "target": target,
        "candidates": sorted(cands),
        "reason": reason[:300],
    }


def interpret(model_input: dict, response) -> dict:
    if not isinstance(response, dict):
        return {"processing": "invalid_response"}
    choices = response.get("choices")
    if not (
        isinstance(choices, list)
        and len(choices) == 1
        and isinstance(choices[0], dict)
        and isinstance(choices[0].get("message"), dict)
    ):
        return {"processing": "invalid_response"}
    if response.get("model") != MODEL or response.get("provider") != PROVIDER_NAME:
        return {"processing": "foreign_provider_or_model"}
    choice, raw = choices[0], choices[0]["message"].get("content")
    if (
        choice.get("finish_reason") != "stop"
        or choice["message"].get("tool_calls")
        or not isinstance(raw, str)
    ):
        return {"processing": "unfinished_or_nontext_response"}
    try:
        return {
            "processing": "valid",
            "raw": raw,
            "proposal": validate(model_input, raw),
        }
    except (ValueError, TypeError, KeyError):
        return {"processing": "invalid_proposal", "raw": raw}


def http_transport(payload: dict, key: str) -> dict:
    call = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(call, timeout=90) as stream:
        return json.load(stream)


def execute(
    experiment: Path,
    key: str | None,
    transport: Callable[[dict, str], dict] = http_transport,
) -> dict:
    """One attempt per request, after checking the freeze. Failures stay in the population."""
    freeze = json.loads((experiment / "freeze.json").read_text("utf-8"))
    if freeze.get("model") != MODEL or freeze.get("harnessSha256") != file_sha(
        Path(__file__)
    ):
        raise Refusal("harness or model differs from the freeze")
    if freeze.get("producerSha256") != file_sha(Path(producer.__file__)):
        raise Refusal("producer differs from the freeze")
    if freeze.get("requestsSha256") != file_sha(experiment / "requests.json"):
        raise Refusal("requests differ from the freeze")
    budget, bound = freeze.get("budgetUsd"), freeze.get("costBoundUsd")
    if not (
        isinstance(budget, (int, float))
        and math.isfinite(budget)
        and 0 < budget <= BUDGET_CEILING_USD
        and isinstance(bound, (int, float))
        and bound <= budget
    ):
        raise Refusal("budget or cost bound out of range")
    requests = json.loads((experiment / "requests.json").read_text("utf-8"))["requests"]
    if any(sha(body(r["input"])) != r["identity"] for r in requests):
        raise Refusal("a request identity does not match its content")
    if sum(cost_bound(body(r["input"])) for r in requests) > budget:
        raise Refusal("cost bound exceeds budget")
    if not key:
        raise Refusal("OPENROUTER_API_KEY is absent")
    output = experiment / "outputs.jsonl"
    if output.exists():
        raise Refusal("execution already attempted")

    def one(r):
        result = {
            "identity": r["identity"],
            "mention": r["mention"],
            "attempts": 1,
            "processing": "transport_failure",
        }
        start = time.monotonic()
        try:
            response = transport(body(r["input"]), key)
            result.update(interpret(r["input"], response))
            if isinstance(response, dict):
                result.update(
                    generationId=response.get("id"),
                    provider=response.get("provider"),
                    model=response.get("model"),
                    usage=response.get("usage"),
                )
        except urllib.error.HTTPError as error:
            # Error bodies can echo inputs; keep only the status, never credentials.
            result.update(processing="http_failure", httpStatus=error.code)
        except (OSError, ValueError, KeyError, TypeError, http.client.HTTPException) as error:
            result.update(
                processing="transport_failure", errorClass=type(error).__name__
            )
        result["seconds"] = round(time.monotonic() - start, 3)
        return result

    results = []
    with output.open(
        "x", encoding="utf-8"
    ) as stream, concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        for result in pool.map(one, requests):
            results.append(result)
            stream.write(json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n")
            stream.flush()
    costs = [
        r["usage"].get("cost") for r in results if isinstance(r.get("usage"), dict)
    ]
    reported = [c for c in costs if isinstance(c, (int, float))]
    summary = {
        "freezeSha256": file_sha(experiment / "freeze.json"),
        "outputsSha256": file_sha(output),
        "requested": len(requests),
        "observed": len(results),
        "attemptedBoundUsd": bound,
        "reportedCostUsd": sum(reported),
        "reportedCostCount": len(reported),
        # Requests without a reported cost are charged at their bound.
        "accountedUpperBoundUsd": sum(reported)
        + sum(
            cost_bound(body(r["input"]))
            for r, res in zip(requests, results)
            if not (
                isinstance(res.get("usage"), dict)
                and isinstance(res["usage"].get("cost"), (int, float))
            )
        ),
    }
    (experiment / "execution.json").write_bytes(canonical(summary) + b"\n")
    return summary


# --- materialize -------------------------------------------------------------------------------


def proposals_from(inputs: dict, results: list[dict]) -> tuple[list[dict], list[dict]]:
    """Recorded model outputs as proposals, after the producer's deixis guards (section 4)."""
    prod = inputs["producer"]
    proposals, outcomes = [], []
    for r in sorted(results, key=lambda x: x["mention"]):
        m = r["mention"]
        if r.get("processing") != "valid":
            outcomes.append({"mention": m, "outcome": r.get("processing", "missing")})
            continue
        p = r["proposal"]
        if p["decision"] == "deictic":
            admitted, reason = producer.guard_deixis(prod, m, p["target"])
            guard = (
                {"admitted": True}
                if admitted
                else {"admitted": False, "reason": reason}
            )
            proposals.append(
                {
                    "id": f"p-model-{m}",
                    "kind": "deictic",
                    "left": m,
                    "right": p["target"],
                    "producer": guard,
                }
            )
            outcomes.append(
                {
                    "mention": m,
                    "outcome": "deictic",
                    "target": p["target"],
                    "guard": guard,
                }
            )
        elif p["decision"] == "candidates":
            for v in p["candidates"]:
                proposals.append(
                    {
                        "id": f"p-hint-{m}-{v}",
                        "kind": "hint",
                        "left": m,
                        "right": v,
                        "basis": "CompatibleDescription",
                        "direction": "Toward",
                        "producer": {"admitted": True},
                    }
                )
            outcomes.append(
                {"mention": m, "outcome": "candidates", "candidates": p["candidates"]}
            )
        else:
            outcomes.append({"mention": m, "outcome": "absent-or-unclear"})
    return proposals, outcomes


def materialize(run_dir: Path, experiment: Path, out: Path) -> dict:
    """Offline: the producer's inputs plus the model's proposals, for `sourceIdentity build`."""
    freeze = json.loads((experiment / "freeze.json").read_text("utf-8"))
    inputs_path = run_dir / "inputs.json"
    if freeze.get("inputsSha256") != file_sha(inputs_path):
        raise Refusal("the producer inputs differ from the freeze")
    if freeze.get("producerSha256") != file_sha(Path(producer.__file__)):
        raise Refusal("producer differs from the freeze")
    summary = json.loads((experiment / "execution.json").read_text("utf-8"))
    output = experiment / "outputs.jsonl"
    if summary.get("outputsSha256") != file_sha(output):
        raise Refusal("recordings differ from the execution summary")
    requests = json.loads((experiment / "requests.json").read_text("utf-8"))["requests"]
    results = [
        json.loads(line) for line in output.read_text("utf-8").splitlines() if line
    ]
    if sorted(r["identity"] for r in results) != sorted(
        r["identity"] for r in requests
    ):
        raise Refusal("recordings do not cover exactly the frozen requests")
    inputs = json.loads(inputs_path.read_text("utf-8"))
    if freeze.get("requestsSha256") != file_sha(experiment / "requests.json"):
        raise Refusal("requests differ from the freeze")
    if summary.get("freezeSha256") != file_sha(experiment / "freeze.json"):
        raise Refusal("the execution summary belongs to another freeze")
    # The recorded proposal is re-derived from the raw reply and the frozen input, so a recording
    # cannot carry a proposal the reply did not contain.
    by_identity = {q["identity"]: q for q in requests}
    for res in results:
        q = by_identity[res["identity"]]
        if res.get("mention") != q["mention"]:
            raise Refusal("a recording names another mention than its request")
        if res.get("processing") == "valid":
            try:
                again = validate(q["input"], res.get("raw"))
            except (ValueError, TypeError, KeyError):
                raise Refusal("a recorded reply does not validate against its request")
            if again != res.get("proposal"):
                raise Refusal("a recorded proposal differs from its reply")
    proposals, outcomes = proposals_from(inputs, results)
    doc = dict(inputs)
    doc["inputs"] = sorted(
        inputs["inputs"] + [{"role": "model-recordings", "sha256": file_sha(output)}],
        key=lambda e: e["role"],
    )
    doc["proposals"] = inputs["proposals"] + sorted(proposals, key=lambda p: p["id"])
    doc["producer"] = dict(
        inputs["producer"],
        model={
            "model": MODEL,
            "freezeSha256": file_sha(experiment / "freeze.json"),
            "outcomes": outcomes,
        },
    )
    if out.exists():
        raise Refusal("output already exists")
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix=f".{out.name}-", dir=out.parent))
    (tmp / "inputs.json").write_text(
        json.dumps(doc, indent=2, sort_keys=True) + "\n", "utf-8"
    )
    os.rename(tmp, out)
    return {"proposals": len(proposals), "outcomes": outcomes}


def main(argv: list[str]) -> int:
    try:
        if len(argv) == 4 and argv[0] == "prepare":
            print(
                json.dumps(
                    prepare(
                        Path(argv[1]), Path(argv[2]), Path(argv[3]), BUDGET_CEILING_USD
                    ),
                    indent=2,
                )
            )
        elif len(argv) == 2 and argv[0] == "execute":
            print(
                json.dumps(
                    execute(Path(argv[1]), os.environ.get("OPENROUTER_API_KEY")),
                    indent=2,
                )
            )
        elif len(argv) == 4 and argv[0] == "materialize":
            print(
                json.dumps(
                    materialize(Path(argv[1]), Path(argv[2]), Path(argv[3])), indent=2
                )
            )
        else:
            print(__doc__.split("Usage:")[1], file=sys.stderr)
            return 2
    except producer.Refusal as e:
        print(f"refused: {e}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
