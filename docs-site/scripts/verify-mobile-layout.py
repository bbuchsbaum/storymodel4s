from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
from threading import Thread

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
PAGES = (
    "/method/represent-recall/",
    "/method/build-interpretation/",
    "/scala/window-features/",
)


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, _format, *_args):
        pass


def assert_page_width(page, path, width):
    dimensions = page.evaluate(
        """() => ({
          viewport: document.documentElement.clientWidth,
          page: document.documentElement.scrollWidth,
          body: document.body.scrollWidth,
          blocks: [...document.querySelectorAll('.sl-markdown-content pre')].map((el) => ({
            client: el.clientWidth,
            scroll: el.scrollWidth,
            overflowX: getComputedStyle(el).overflowX,
            left: el.getBoundingClientRect().left,
            right: el.getBoundingClientRect().right
          }))
        })"""
    )
    assert dimensions["viewport"] == width, (path, dimensions)
    assert dimensions["page"] <= width, (path, dimensions)
    assert dimensions["body"] <= width, (path, dimensions)
    assert dimensions["blocks"], f"{path}: expected at least one code block"
    for block in dimensions["blocks"]:
        assert block["overflowX"] in ("auto", "scroll"), (path, block)
        assert block["left"] >= -0.5 and block["right"] <= width + 0.5, (path, block)
    return dimensions


def assert_mobile_menu(page, path):
    menu = page.locator('button[aria-controls="starlight__sidebar"]')
    sidebar = page.locator("#starlight__sidebar")
    assert menu.is_visible(), f"{path}: mobile Menu button is not visible"
    assert sidebar.evaluate("el => getComputedStyle(el).visibility") == "hidden"
    menu.click()
    assert sidebar.evaluate("el => getComputedStyle(el).visibility") == "visible"
    menu.click()
    assert sidebar.evaluate("el => getComputedStyle(el).visibility") == "hidden"


def main():
    assert DIST.is_dir(), "docs-site/dist is missing; run npm run build first"

    handler = partial(QuietHandler, directory=str(DIST))
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"

    executable = os.environ.get("PLAYWRIGHT_CHROMIUM_EXECUTABLE")
    launch = {"headless": True}
    if executable:
        launch["executable_path"] = executable

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(**launch)
            try:
                page = browser.new_page(viewport={"width": 390, "height": 844})
                for path in PAGES:
                    page.goto(base + path, wait_until="networkidle")
                    dimensions = assert_page_width(page, path, 390)
                    assert any(
                        block["scroll"] > block["client"]
                        for block in dimensions["blocks"]
                    ), f"{path}: control code block no longer exercises local scrolling"
                    assert_mobile_menu(page, path)
                    print(f"PASS narrow {path} width=390 local-scroll=present menu=open-close")

                page.set_viewport_size({"width": 1440, "height": 900})
                for path in PAGES:
                    page.goto(base + path, wait_until="networkidle")
                    assert_page_width(page, path, 1440)
                    print(f"PASS wide   {path} width=1440 no-page-overflow")
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == "__main__":
    main()
