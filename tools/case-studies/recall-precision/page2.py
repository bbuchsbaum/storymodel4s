"""Interactive progress page (Observable Plot, SVG, data embedded inline).

Usage: page2.py VIZ_JSON OUT.html     (VIZ_JSON from viz_data.py; text from notes.json)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main():
    data = Path(sys.argv[1]).read_text()
    notes = json.loads((HERE / "notes.json").read_text())
    html = (HERE / "page2.html").read_text()
    html = html.replace("/*__DATA__*/", data.replace("</", "<\\/"))
    html = html.replace("/*__NOTES__*/", json.dumps(notes).replace("</", "<\\/"))
    Path(sys.argv[2]).write_text(html)
    print(sys.argv[2], len(html))


if __name__ == "__main__":
    main()
