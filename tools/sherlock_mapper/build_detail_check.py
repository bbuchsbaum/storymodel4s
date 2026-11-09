#!/usr/bin/env python3
"""Short follow-up check: is the graded verifier's named detail error true of the film?

Selects the claims the owner rated "supports" (with no note) that the verifier called gist-detail,
and asks only whether the named detail is right. Writes OUT/detail-check/index.html (local, holds
recall text) and key.json. Score with: build_detail_check.py --score answers.json
"""

import argparse
import html
import json

import common as c
from build_claim_check import PAGE, VIDEO

DIR = c.OUT / "detail-check"
OPTS = [
    ("right", "Right: the film differs from the recall in exactly this way"),
    ("partly", "Partly right, or too trivial to count as an error"),
    ("wrong", "Wrong: the recall matches the film on this point"),
    ("cant", "Can't tell"),
]

HEADER = """<header><b>Detail check</b>, one question per item. The checker says the recall gets
the gist right but one detail wrong, and names it in the box. <b>Is that statement about the
film correct?</b> Judge only the named detail. The clips are where the checker looked.
<span id="prog"></span>
<button onclick="exportAns()">Export answers</button></header>"""


def build(verify_run, answers):
    rows = {r.id: r for r in c.film_rows()}
    ans = json.loads(open(answers).read())["answers"]
    key = {
        str(k["item"]): k
        for k in json.loads((c.OUT / "claim-check/key.json").read_text())
    }
    ver = json.loads(
        (c.OUT / "claim-verify" / verify_run / "verdicts.json").read_text()
    )
    picks = [
        i
        for i in sorted(key, key=int)
        if ans.get(i, {}).get("choice") == "supports"
        and not ans.get(i, {}).get("note")
        and ver.get(i, {}).get("verdict") == "gist-detail"
    ]
    cards, out = [], []
    for n, i in enumerate(picks, 1):
        k, v = key[i], ver[i]
        c.guard(k["participant"])
        text = {u.index: u.text for u in c.units(k["participant"])}
        idx, cl = k["unit"], k["claim"]
        ctx = "".join(
            f'<p class="{"cur" if j == idx else "ctx"}">{html.escape(text[j])}</p>'
            for j in (idx - 1, idx, idx + 1)
            if j in text
        )
        cite = []
        for r in (v.get("rows") or []) + (cl.get("rows") or []):
            if isinstance(r, int) and r in rows and r not in cite:
                cite.append(r)
        ev = (
            "".join(
                f"<li><button onclick=\"play('{rows[r].part}',{rows[r].start},{rows[r].end})\">▶ "
                f"{rows[r].part} {c.fmt(rows[r].start)}</button> <b>r{r}</b> (scene {rows[r].scene}) "
                f"{html.escape(rows[r].desc)}</li>"
                for r in cite[:5]
            )
            or "<li><i>no rows cited</i></li>"
        )
        radios = "".join(
            f'<label><input type="radio" name="q{n}" value="{val}" onchange="save()"> {t}</label>'
            for val, t in OPTS
        )
        who = ", ".join(cl.get("who") or [])
        cards.append(
            f'<section id="i{n}"><h2>Item {n} <span class="pid">(claim-check item {i})</span></h2>'
            f'<div class="recall">{ctx}</div>'
            f'<p class="pid">Claim: {html.escape(who)} — {html.escape(str(cl.get("what")))}</p>'
            f'<div class="claim"><b>Checker says the detail is wrong:</b> '
            f'{html.escape(v.get("detail") or "")}</div>'
            f'<ul class="ev">{ev}</ul><div class="opts">{radios}</div>'
            f'<input class="note" id="n{n}" placeholder="optional note" oninput="save()"></section>'
        )
        out.append(dict(item=n, claimCheckItem=int(i), detail=v.get("detail")))
    page = PAGE.split("<header>")[0] + HEADER + "<main>" + PAGE.split("<main>", 1)[1]
    page = (
        page.replace("{{CARDS}}", "\n".join(cards))
        .replace("{{N}}", str(len(picks)))
        .replace("{{VA}}", VIDEO["a"].as_uri())
        .replace("{{VB}}", VIDEO["b"].as_uri())
        .replace("claimcheck-v1", "detailcheck-v1")
        .replace("claim-check-answers.json", "detail-check-answers.json")
        .replace("<title>Claim check</title>", "<title>Detail check</title>")
    )
    DIR.mkdir(parents=True, exist_ok=True)
    (DIR / "index.html").write_text(page)
    (DIR / "key.json").write_text(json.dumps(out, indent=1))
    print(DIR / "index.html", len(picks), "items")


def score(path):
    from collections import Counter

    ans = json.loads(open(path).read())["answers"]
    print(Counter(a.get("choice") for a in ans.values()))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify-run", default="owner50-v1")
    ap.add_argument(
        "--answers", default=str(c.OUT / "claim-check/answers-owner-20261108.json")
    )
    ap.add_argument("--score")
    a = ap.parse_args()
    score(a.score) if a.score else build(a.verify_run, a.answers)
