#!/usr/bin/env python3
"""Second-stage, graded verification of extracted recall claims against the whole film record.

The v3 extractor's own three-way fidelity label (supported / contradicted / not-in-film) failed an
owner blind check: its "contradicted" agreed with the owner on 6 of 30 claims, because recall is
graded (gist right, a detail wrong) and the label was not. This pass re-judges each claim blind to
the extractor's label, on a graded scale (VERDICTS).

Usage:
  verify_claims.py --key claim-check/key.json --name RUN    # re-judge the owner-checked items
  verify_claims.py --run v3-opus --name RUN                 # re-judge every claim of a run
  verify_claims.py --compare RUN [--answers answers.json]   # confusion table against the owner
"""

import argparse
import json
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

import common as c
from llm import call, parse_json

VERDICTS = [
    "supported",
    "gist-detail",
    "contradicted",
    "inference",
    "unverifiable",
    "invented",
    "misread",
]

SYSTEM = """You verify claims extracted from participants' free recall of a film against the \
film itself. Participants watched the first episode of BBC's Sherlock ("A Study in Pink", 50 \
annotated scenes) and recalled it aloud. Another annotator split the recall into units and \
extracted short claims ("who" + "what", sometimes "where"). You get the full timed film \
annotation (per-shot descriptions rows rNNN, people, places, transcribed dialogue, visual \
summaries), the recall sentence each claim came from with its neighbours, and the claim.

Recall memory is graded. People keep the gist of events and lose or distort details; that is the \
normal case, not an error in the gist. Judge in two steps.

Step 1, the claim against the sentence. If the claim states something the bold recall sentence \
does not say or imply, or attributes it to the wrong person, the verdict is "misread". Character \
names in "who" are the annotator's resolution of the participant's words; accept them if the \
resolution is reasonable. A "where" field is the annotator's location label: ignore it.

Step 2, the claim against the film as a whole (not only the cited rows). Choose one:
- "supported": the film bears out the claim as stated. Paraphrase, compression, synonyms, \
  vagueness and omission are all fine.
- "gist-detail": the event or situation is recognisably the film's, but one or more specific \
  details are wrong: a date or number, exact wording, who said a line, a minor action or manner, \
  an object, a place detail. Name the wrong detail in "detail".
- "contradicted": the central element is false: the event did not happen, happened to or by \
  someone else in a way that changes what happened, or its outcome is reversed. Use this only \
  when the gist itself is wrong, never for a wrong detail.
- "inference": the participant's own conclusion or interpretation (a motive, identity, cause, \
  something offscreen) that the film implies or leaves open but does not show or state.
- "unverifiable": the film record available to you cannot settle it either way, for example a \
  visual detail the annotation does not describe. Absence from the annotation is not evidence \
  that something is absent from the film.
- "invented": no basis in the film and not a reasonable inference from it.

Reply with JSON only: {"claims": [{"k": <claim key>, "verdict": "...", "detail": <string or \
null>, "rows": [film rows you relied on]}]} covering every claim exactly once."""


def items_from_key(path):
    out = []
    for x in json.loads(open(path).read()):
        out.append(
            dict(
                k=str(x["item"]),
                participant=x["participant"],
                unit=x["unit"],
                claim=x["claim"],
            )
        )
    return out


def items_from_run(run):
    out = []
    for p in sorted((c.OUT / run).glob("NN*.json")):
        c.guard(p.stem)
        for u in json.loads(p.read_text())["units"]:
            for j, cl in enumerate(u.get("claims") or []):
                out.append(
                    dict(
                        k=f"{p.stem[:4]}-{u['index']}-{j}",
                        participant=p.stem,
                        unit=u["index"],
                        claim=cl,
                    )
                )
    return out


def render(it, text):
    ctx = "\n".join(
        f"{'**' if j == it['unit'] else '  '}[{j}] {text[j]}{'**' if j == it['unit'] else ''}"
        for j in (it["unit"] - 1, it["unit"], it["unit"] + 1)
        if j in text
    )
    cl = it["claim"]
    return (
        f"CLAIM {it['k']}\nRecall (bold is the source sentence):\n{ctx}\n"
        f"Claim: who={json.dumps(cl.get('who'))} what={json.dumps(cl.get('what'))} "
        f"cited rows={json.dumps(cl.get('rows'))}\n"
    )


def verify(name, its, outline, model, chunk):
    text = {u.index: u.text for u in c.units(name)}
    out = {}
    for lo in range(0, len(its), chunk):
        batch = its[lo : lo + chunk]
        prompt = f"FILM ANNOTATION\n{outline}\n\nCLAIMS TO VERIFY\n\n" + "\n".join(
            render(i, text) for i in batch
        )
        for attempt in range(3):
            sfx = (
                ""
                if attempt == 0
                else f"\n\nReply with strictly valid JSON only ({attempt})."
            )
            try:
                for x in parse_json(call(prompt + sfx, SYSTEM, model=model))["claims"]:
                    if x.get("verdict") in VERDICTS:
                        out[str(x["k"])] = x
                break
            except (ValueError, KeyError, TypeError):
                continue
    return out


OWNER = {
    "supports": "supports",
    "contradicts": "contradicts",
    "misread": "misread",
    "cant": "can't tell",
    "absent": "not in film",
}


def compare(name, answers):
    d = c.OUT / "claim-check"
    res = json.loads((c.OUT / "claim-verify" / name / "verdicts.json").read_text())
    ans = json.loads(open(answers).read())["answers"]
    key = {str(k["item"]): k for k in json.loads((d / "key.json").read_text())}
    t = Counter()
    for i, k in key.items():
        t[(ans.get(i, {}).get("choice"), res.get(i, {}).get("verdict"))] += 1
    cols = VERDICTS + [None]
    print("owner \\ verifier".ljust(14), " ".join(str(v)[:8].rjust(8) for v in cols))
    for o in OWNER:
        print(o.ljust(14), " ".join(str(t[(o, v)]).rjust(8) for v in cols))
    hard = sum(t[(o, "contradicted")] for o in OWNER)
    print(
        "verifier contradicted:",
        hard,
        "| of which owner contradicts:",
        t[("contradicts", "contradicted")],
        "| owner supports called contradicted:",
        t[("supports", "contradicted")],
    )
    faithful = {"supported", "gist-detail"}
    agree = sum(n for (o, v), n in t.items() if o == "supports" and v in faithful)
    print(
        "owner supports judged supported or gist-detail:",
        agree,
        "/",
        sum(n for (o, _), n in t.items() if o == "supports"),
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key")
    ap.add_argument("--run")
    ap.add_argument("--name")
    ap.add_argument("--compare")
    ap.add_argument(
        "--answers", default=str(c.OUT / "claim-check/answers-owner-20261108.json")
    )
    ap.add_argument("--model", default="opus")
    ap.add_argument("--chunk", type=int, default=15)
    ap.add_argument("--jobs", type=int, default=4)
    a = ap.parse_args()
    if a.compare:
        return compare(a.compare, a.answers)
    its = items_from_key(a.key) if a.key else items_from_run(a.run)
    by = defaultdict(list)
    for it in its:
        c.guard(it["participant"])
        by[it["participant"]].append(it)
    outline, _ = c.film_outline()
    res = {}
    with ThreadPoolExecutor(a.jobs) as ex:
        for r in ex.map(
            lambda n: verify(n, by[n], outline, a.model, a.chunk), sorted(by)
        ):
            res.update(r)
    d = c.OUT / "claim-verify" / a.name
    d.mkdir(parents=True, exist_ok=True)
    (d / "verdicts.json").write_text(json.dumps(res, indent=1))
    print(
        len(res), "/", len(its), "verdicts", Counter(x["verdict"] for x in res.values())
    )


if __name__ == "__main__":
    main()
