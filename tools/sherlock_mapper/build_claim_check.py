#!/usr/bin/env python3
"""Build a blind human check of v3 claim fidelity (development participants only).

Usage: build_claim_check.py [--run v3-opus] [--seed 11]
Writes OUT/claim-check/index.html (no model verdicts) and OUT/claim-check/key.json (verdicts).
Score with: build_claim_check.py --score answers.json
"""

import argparse
import html
import json
import random

import common as c

DIR = c.OUT / "claim-check"
VIDEO = {
    "a": c.DATA / "sherlock/media/Sherlock_part1_imovie.m4v",
    "b": c.DATA / "sherlock/media/Sherlock_part2_imovie.m4v",
}
QUOTA = {"contradicted": 30, "not-in-film": 10, "supported": 10}


def build(run, seed):
    rows = {r.id: r for r in c.film_rows()}
    pools = {k: [] for k in QUOTA}
    for p in sorted((c.OUT / run).glob("NN*.json")):
        c.guard(p.stem)
        us = c.units(p.stem)
        text = {u.index: u.text for u in us}
        for u in json.loads(p.read_text())["units"]:
            for cl in u.get("claims") or []:
                if cl.get("fidelity") in pools:
                    pools[cl["fidelity"]].append((p.stem, u["index"], cl, text))
    rng = random.Random(seed)
    items = []
    for k, n in QUOTA.items():
        items += [(k, x) for x in rng.sample(pools[k], min(n, len(pools[k])))]
    rng.shuffle(items)
    key, cards = [], []
    for i, (verdict, (name, idx, cl, text)) in enumerate(items, 1):
        cited = [
            rows[r] for r in (cl.get("rows") or []) if isinstance(r, int) and r in rows
        ]
        key.append(
            dict(item=i, participant=name, unit=idx, modelFidelity=verdict, claim=cl)
        )
        ctx = "".join(
            f'<p class="{"cur" if j == idx else "ctx"}">{html.escape(text[j])}</p>'
            for j in (idx - 1, idx, idx + 1)
            if j in text
        )
        ev = (
            "".join(
                f"<li><button onclick=\"play('{r.part}',{r.start},{r.end})\">▶ {r.part} "
                f"{c.fmt(r.start)}</button> <b>r{r.id}</b> (scene {r.scene}) {html.escape(r.desc)}</li>"
                for r in cited
            )
            or "<li><i>no rows cited</i></li>"
        )
        who = ", ".join(cl.get("who") or [])
        opts = [
            ("supports", "Film supports the claim"),
            ("contradicts", "Film contradicts it"),
            ("absent", "Not in the film"),
            ("cant", "Can't tell"),
            ("misread", "Claim misreads the recall sentence"),
        ]
        radios = "".join(
            f'<label><input type="radio" name="q{i}" value="{v}" onchange="save()"> {t}</label>'
            for v, t in opts
        )
        cards.append(
            f'<section id="i{i}"><h2>Item {i} <span class="pid">{name[:4]} unit {idx}</span></h2>'
            f'<div class="recall">{ctx}</div>'
            f'<div class="claim"><b>Extracted claim:</b> {html.escape(who)} — '
            f'{html.escape(str(cl.get("what")))}'
            f'{" @ " + html.escape(str(cl["where"])) if cl.get("where") else ""}</div>'
            f'<ul class="ev">{ev}</ul><div class="opts">{radios}</div>'
            f'<input class="note" id="n{i}" placeholder="optional note" oninput="save()"></section>'
        )
    page = (
        PAGE.replace("{{CARDS}}", "\n".join(cards))
        .replace("{{N}}", str(len(items)))
        .replace("{{VA}}", VIDEO["a"].as_uri())
        .replace("{{VB}}", VIDEO["b"].as_uri())
    )
    DIR.mkdir(parents=True, exist_ok=True)
    (DIR / "index.html").write_text(page)
    (DIR / "key.json").write_text(json.dumps(key, indent=1))
    print(
        DIR / "index.html", len(items), "items", {k: len(v) for k, v in pools.items()}
    )


def score(path):
    ans = json.loads(open(path).read())["answers"]
    key = {k["item"]: k for k in json.loads((DIR / "key.json").read_text())}
    from collections import Counter

    t = Counter()
    for i, k in key.items():
        a = ans.get(str(i), {}).get("choice")
        t[(k["modelFidelity"], a)] += 1
    for k in QUOTA:
        row = {a: n for (m, a), n in t.items() if m == k}
        print(k, row)
    flagged = sum(n for (m, a), n in t.items() if m == "contradicted" and a)
    agree = t[("contradicted", "contradicts")]
    print("contradiction precision", agree, "/", flagged)


PAGE = r"""<!doctype html><html><head><meta charset="utf-8"><title>Claim check</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
:root{--bg:#fbfaf7;--fg:#1d1d1b;--mut:#6b6b66;--card:#fff;--line:#e4e1da;--acc:#2d5b8a}
@media (prefers-color-scheme:dark){:root{--bg:#151514;--fg:#ecebe6;--mut:#9a9890;--card:#1f1f1d;--line:#34332f;--acc:#8bb4e0}}
body{background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,system-ui,sans-serif;margin:0}
header{position:sticky;top:0;background:var(--bg);border-bottom:1px solid var(--line);padding:10px 16px;z-index:2}
main{display:grid;grid-template-columns:minmax(0,1fr) 420px;gap:16px;padding:16px}
@media (max-width:900px){main{grid-template-columns:1fr}aside{position:static!important}}
aside{position:sticky;top:64px;align-self:start}video{width:100%;background:#000;border-radius:6px}
section{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 16px;margin-bottom:14px}
h2{font-size:15px;margin:0 0 6px}.pid{color:var(--mut);font-weight:400;font-size:13px}
.ctx{color:var(--mut);margin:2px 0}.cur{margin:2px 0;font-weight:600}
.claim{margin:8px 0;padding:6px 10px;border-left:3px solid var(--acc)}.ev{padding-left:18px;font-size:14px}
.ev button{font-size:12px}.opts{display:flex;flex-wrap:wrap;gap:6px 14px;margin:8px 0}
.note{width:100%;box-sizing:border-box;padding:4px 6px;background:transparent;color:var(--fg);border:1px solid var(--line);border-radius:4px}
button{cursor:pointer}
</style></head><body>
<header><b>Claim check</b>: for each item, judge the extracted claim against the film. The
model's own verdict is hidden. <span id="prog"></span>
<button onclick="exportAns()">Export answers</button></header>
<main><div>{{CARDS}}</div><aside><video id="v" controls preload="none"></video>
<p class="pid">Clip plays the cited annotation row (2 s lead-in).</p></aside></main>
<script>
const SRC={a:"{{VA}}",b:"{{VB}}"},N={{N}},KEY="claimcheck-v1";let stopAt=null;
const v=document.getElementById("v");
function play(p,s,e){if(!v.src.endsWith(SRC[p]))v.src=SRC[p];v.currentTime=Math.max(0,s-2);stopAt=e+1;v.play();}
v.addEventListener("timeupdate",()=>{if(stopAt!==null&&v.currentTime>stopAt){v.pause();stopAt=null;}});
function collect(){const a={};for(let i=1;i<=N;i++){const r=document.querySelector(`input[name=q${i}]:checked`);
const n=document.getElementById("n"+i).value;if(r||n)a[i]={choice:r?r.value:null,note:n};}return a;}
function save(){const a=collect();try{localStorage.setItem(KEY,JSON.stringify(a));}catch(e){}
document.getElementById("prog").textContent=`${Object.values(a).filter(x=>x.choice).length}/${N} answered`;}
function load(){let a={};try{a=JSON.parse(localStorage.getItem(KEY)||"{}");}catch(e){}
for(const[i,x]of Object.entries(a)){if(x.choice){const r=document.querySelector(`input[name=q${i}][value=${x.choice}]`);if(r)r.checked=true;}
const n=document.getElementById("n"+i);if(n)n.value=x.note||"";}save();}
function exportAns(){const b=new Blob([JSON.stringify({answers:collect()},null,1)],{type:"application/json"});
const l=document.createElement("a");l.href=URL.createObjectURL(b);l.download="claim-check-answers.json";l.click();}
load();
</script></body></html>"""


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", default="v3-opus")
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--score")
    a = ap.parse_args()
    score(a.score) if a.score else build(a.run, a.seed)
