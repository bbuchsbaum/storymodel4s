"""Render the case-study progress page (HTML) from run summaries, figures and notes.json.

Usage: page.py OUT.html
The page carries numbers and figures only: no recall or film text.
"""

from __future__ import annotations

import base64
import html
import json
import sys
from pathlib import Path

import data as D

HERE = Path(__file__).resolve().parent


def img(path: Path, alt: str) -> str:
    if not path.exists():
        return f'<p class="missing">missing figure: {html.escape(path.name)}</p>'
    b = base64.b64encode(path.read_bytes()).decode()
    return (
        f'<img src="data:image/png;base64,{b}" alt="{html.escape(alt)}" loading="lazy">'
    )


def fmt(v, kind):
    if v is None:
        return "—"
    if kind == "pct":
        return f"{v:.0%}"
    if kind == "s":
        return f"{v:.0f}"
    if kind == "b":
        return f"{v:.2f}"
    return f"{v:.2f}"


def table(notes):
    cols = notes["metrics"]
    head = "".join(
        f'<th scope="col" title="{html.escape(c["help"])}">{html.escape(c["label"])}</th>'
        for c in cols
    )
    body = []
    for it in notes["iterations"]:
        for run in it.get("runs", []):
            p = D.OUT / "runs" / run["label"] / "summary.json"
            if not p.exists():
                continue
            s = json.loads(p.read_text())
            m, ci = s.get("participant_mean", {}), s.get("participant_boot95", {})
            nul = s.get("foreign_null", {})
            cells = []
            for c in cols:
                src = nul if c.get("source") == "null" else m
                v = src.get(c["key"])
                txt = fmt(v, c["kind"])
                if c["key"] in ci and c.get("source") != "null":
                    lo, hi = ci[c["key"]]
                    txt += f'<span class="ci">{fmt(lo, c["kind"])}–{fmt(hi, c["kind"])}</span>'
                cells.append(f"<td>{txt}</td>")
            cls = ' class="current"' if run.get("current") else ""
            body.append(
                f'<tr{cls}><th scope="row">{html.escape(it["id"])}<span class="run">'
                f'{html.escape(run["name"])}</span></th>{"".join(cells)}</tr>'
            )
    for ref in notes.get("reference_rows", []):
        cells = "".join(
            f"<td>{html.escape(ref['values'].get(c['key'], ''))}</td>" for c in cols
        )
        body.append(
            f'<tr class="ref"><th scope="row">{html.escape(ref["name"])}</th>{cells}</tr>'
        )
    return (
        f'<div class="tablewrap"><table><thead><tr><th></th>{head}</tr></thead>'
        f'<tbody>{"".join(body)}</tbody></table></div>'
    )


def section(it):
    parts = [
        f'<section class="iter" id="{html.escape(it["id"])}">',
        f'<h2><span class="tag">{html.escape(it["id"])}</span> {html.escape(it["title"])}</h2>',
        f'<p class="date">{html.escape(it["date"])}</p>',
    ]
    for para in it.get("summary", []):
        parts.append(f"<p>{para}</p>")
    figs = it.get("figures", [])
    for f in figs:
        p = D.OUT / "runs" / f["run"] / "fig" / f["file"]
        parts.append(
            f'<figure>{img(p, f["caption"])}<figcaption>{f["caption"]}</figcaption></figure>'
        )
    if it.get("critique"):
        parts.append('<div class="critique"><h3>Critique</h3>')
        for c in it["critique"]:
            parts.append(
                f'<div class="critic"><h4>{html.escape(c["who"])}</h4><ul>'
                + "".join(f"<li>{x}</li>" for x in c["points"])
                + "</ul></div>"
            )
        parts.append("</div>")
    if it.get("decisions"):
        parts.append(
            '<h3>What changed next</h3><ol class="decisions">'
            + "".join(f"<li>{x}</li>" for x in it["decisions"])
            + "</ol>"
        )
    parts.append("</section>")
    return "\n".join(parts)


CSS = (HERE / "page.css").read_text()


def main():
    out = Path(sys.argv[1])
    notes = json.loads((HERE / "notes.json").read_text())
    body = [
        '<meta name="claude:viewport-fit-sides" content="cover">',
        f"<title>{html.escape(notes['title'])}</title>",
        f"<style>{CSS}</style>",
        '<main class="wrap">',
        f'<header><p class="eyebrow">{html.escape(notes["eyebrow"])}</p>'
        f'<h1>{html.escape(notes["title"])}</h1>',
        "".join(f'<p class="lede">{x}</p>' for x in notes["lede"]),
        "</header>",
        "<section><h2>Status</h2>"
        + "".join(f"<p>{x}</p>" for x in notes["status"])
        + "</section>",
        "<section><h2>Every iteration on the same yardstick</h2>",
        '<p class="note">' + notes["table_note"] + "</p>",
        table(notes),
        "</section>",
    ]
    for it in reversed(notes["iterations"]):
        body.append(section(it))
    body.append(
        '<section><h2>Library gaps found so far</h2><ul class="gaps">'
        + "".join(f"<li>{x}</li>" for x in notes["gaps"])
        + "</ul></section>"
    )
    body.append(f'<footer><p>{notes["footer"]}</p></footer></main>')
    out.write_text("\n".join(body))
    print(out, out.stat().st_size)


if __name__ == "__main__":
    main()
