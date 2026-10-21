#!/usr/bin/env python3
"""Bounded, explicitly authorized remote assessor comparison on frozen inputs.

Only the v1 model-input whitelist and unchanged task prompt leave the machine.
Credentials come from the environment and are never included in artifacts or logs.
One attempt per request; failures remain in the population. No tools or model fallback.
"""
import argparse
import concurrent.futures
import json
import math
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

import claim_assessment_probe as v1

ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
MODEL = "deepseek/deepseek-v4.1-flash-1017"
PROVIDER = dict(only=["fireworks"], allow_fallbacks=False, require_parameters=True,
                data_collection="deny", zdr=True, max_price=dict(prompt=0.30, completion=1.20))


def body(request):
    return dict(model=MODEL, temperature=0, max_tokens=4096,
                reasoning=dict(effort="low", exclude=True), provider=PROVIDER,
                stream=False, messages=[dict(role="user", content=v1.PROMPT + "\nINPUT:\n" +
                                            json.dumps(v1.model_input(request), ensure_ascii=False))])


def cost_bound(payload):
    """Conservative byte-based prompt bound plus the entire completion budget, USD.

    Assumes no per-request charge, enforced by provider max_price.request=0 below.
    A 256-token allowance covers chat framing. Pricing is capped by routing.
    """
    prompt_bound = sum(len(m["content"].encode()) for m in payload["messages"]) + 256
    return prompt_bound * 0.30 / 1e6 + payload["max_tokens"] * 1.20 / 1e6


def interpret(request, response):
    invalid = dict(processing="invalid_response", raw=None, proposal=None)
    if not isinstance(response, dict):
        return invalid
    choices = response.get("choices")
    if (not isinstance(choices, list) or len(choices) != 1 or
            not isinstance(choices[0], dict) or
            not isinstance(choices[0].get("message"), dict)):
        return invalid
    if response.get("model") != MODEL or response.get("provider") != "Fireworks":
        return dict(processing="foreign_provider_or_model", raw=None, proposal=None)
    choice = choices[0]; message = choice.get("message", {})
    raw = message.get("content")
    if choice.get("finish_reason") != "stop" or message.get("tool_calls") or not isinstance(raw, str):
        return dict(processing="unfinished_or_nontext_response", raw=raw if isinstance(raw, str) else None,
                    proposal=None)
    try:
        proposal = v1.validate_proposal(request, raw)
        return dict(processing="valid", raw=raw, proposal=proposal)
    except (ValueError, TypeError, KeyError):
        return dict(processing="invalid_proposal", raw=raw, proposal=None)


def execute(record, key):
    request = record["request"]; payload = body(request)
    payload["provider"] = dict(PROVIDER, max_price=dict(prompt=0.30, completion=1.20, request=0))
    encoded = json.dumps(payload, ensure_ascii=False).encode()
    result = dict(condition=record["condition"], question=request["question"], arm=request["arm"],
                  requestIdentity=request["identity"], bodySha256=v1.hashlib.sha256(encoded).hexdigest(),
                  processing="transport_failure", raw=None, proposal=None, attempts=1)
    start = time.monotonic()
    call = urllib.request.Request(ENDPOINT, data=encoded,
                                  headers={"Authorization": "Bearer " + key,
                                           "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(call, timeout=90) as stream:
            response = json.load(stream)
        result.update(interpret(request, response))
        if isinstance(response, dict):
            result.update(generationId=response.get("id"), provider=response.get("provider"),
                          model=response.get("model"), usage=response.get("usage"))
            choices = response.get("choices")
            if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                result["finishReason"] = choices[0].get("finish_reason")
    except urllib.error.HTTPError as error:
        # Error bodies can echo caller inputs. Keep only the HTTP code, never credentials.
        result.update(processing="http_failure", httpStatus=error.code)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result.update(processing="transport_failure", errorClass=type(error).__name__)
    result["seconds"] = time.monotonic() - start
    return result


def run(root):
    freeze = v1.read(root / "freeze.json")
    if freeze["harnessSha256"] != v1.sha(__file__) or freeze["model"] != MODEL:
        raise ValueError("harness/model differs from freeze")
    request_path = (root / "requests.json").absolute()
    if freeze["files"].get(str(request_path)) != v1.sha(request_path):
        raise ValueError("consumed requests not frozen")
    for path, digest in freeze["files"].items():
        if v1.sha(path) != digest:
            raise ValueError("frozen artifact changed")
    records = v1.read(request_path)["executions"]
    keys = [(r["condition"], r["request"]["question"], r["request"]["arm"]) for r in records]
    if not records or len(set(keys)) != len(keys):
        raise ValueError("empty/duplicate executions")
    for record in records:
        r = record["request"]
        if v1.identity({k:v for k,v in r.items() if k != "identity"}) != r["identity"]:
            raise ValueError("request identity mismatch")
    bound = sum(cost_bound(body(r["request"])) for r in records)
    budget = freeze["budgetUsd"]
    if not isinstance(budget, (int, float)) or not math.isfinite(budget) or not 0 < budget <= 3 or bound > budget:
        raise ValueError("conservative budget bound exceeded")
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        raise ValueError("OPENROUTER_API_KEY is absent")
    output = root / "outputs.jsonl"
    if output.exists():
        raise ValueError("execution already attempted")
    results = []
    with output.open("x") as stream, concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        # At most four in flight; one attempt each, with a prevalidated whole-run cost bound.
        for index, result in enumerate(pool.map(lambda r: execute(r, key), records)):
            results.append(result)
            stream.write(json.dumps(result, ensure_ascii=False) + "\n"); stream.flush()
            print(f"Completed {index+1}/{len(records)}: {result['processing']}", flush=True)
    costs = [r.get("usage", {}).get("cost") for r in results if isinstance(r.get("usage"), dict)]
    v1.write(root / "execution.json", dict(freezeSha256=v1.sha(root / "freeze.json"),
             outputsSha256=v1.sha(output), requested=len(records), observed=len(results),
             conservativeCostBoundUsd=bound, reportedCostUsd=sum(c for c in costs if isinstance(c, (float, int))),
             reportedCostCount=sum(isinstance(c, (float, int)) for c in costs), attemptsPerRequest=1))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("experiment", type=Path)
    run(parser.parse_args().experiment)
