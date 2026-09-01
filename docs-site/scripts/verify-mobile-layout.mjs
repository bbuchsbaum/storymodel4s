import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, resolve } from 'node:path';
import { chromium } from '@playwright/test';

const root = resolve(import.meta.dirname, '..');
const dist = resolve(root, 'dist');
const playwrightPackage = JSON.parse(
  await readFile(resolve(root, 'node_modules/@playwright/test/package.json'), 'utf8'),
);
const playwrightVersion = playwrightPackage.version;
const pages = [
  '/method/represent-recall/',
  '/method/build-interpretation/',
  '/scala/window-features/',
];

const contentTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.woff2', 'font/woff2'],
]);

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    const relative = decodeURIComponent(url.pathname).replace(/^\/+/, '');
    const requested = resolve(dist, relative || 'index.html');
    assert(requested === dist || requested.startsWith(`${dist}/`));
    const path = url.pathname.endsWith('/') ? resolve(requested, 'index.html') : requested;
    const body = await readFile(path);
    response.writeHead(200, {
      'content-type': contentTypes.get(extname(path)) ?? 'application/octet-stream',
    });
    response.end(body);
  } catch {
    response.writeHead(404);
    response.end('Not found');
  }
});

const listen = () =>
  new Promise((accept, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', accept);
  });

const closeServer = () =>
  new Promise((accept, reject) => {
    server.close((error) => (error ? reject(error) : accept()));
  });

const pageDimensions = (page) =>
  page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    page: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
    blocks: [...document.querySelectorAll('.sl-markdown-content pre')].map((element) => ({
      client: element.clientWidth,
      scroll: element.scrollWidth,
      overflowX: getComputedStyle(element).overflowX,
      left: element.getBoundingClientRect().left,
      right: element.getBoundingClientRect().right,
    })),
  }));

const assertPageWidth = async (page, path, width) => {
  const dimensions = await pageDimensions(page);
  assert.equal(dimensions.viewport, width, `${path}: unexpected viewport width`);
  assert(dimensions.page <= width, `${path}: document overflow ${dimensions.page} > ${width}`);
  assert(dimensions.body <= width, `${path}: body overflow ${dimensions.body} > ${width}`);
  assert(dimensions.blocks.length > 0, `${path}: expected at least one code block`);
  for (const block of dimensions.blocks) {
    assert(['auto', 'scroll'].includes(block.overflowX), `${path}: code overflow is ${block.overflowX}`);
    assert(block.left >= -0.5 && block.right <= width + 0.5, `${path}: code block escapes viewport`);
  }
  return dimensions;
};

const assertMobileMenu = async (page, path) => {
  const menu = page.locator('button[aria-controls="starlight__sidebar"]');
  const sidebar = page.locator('#starlight__sidebar');
  assert(await menu.isVisible(), `${path}: mobile Menu button is not visible`);
  assert.equal(await sidebar.evaluate((element) => getComputedStyle(element).visibility), 'hidden');
  await menu.click();
  await page.waitForFunction(() => getComputedStyle(document.querySelector('#starlight__sidebar')).visibility === 'visible');
  await menu.click();
  await page.waitForFunction(() => getComputedStyle(document.querySelector('#starlight__sidebar')).visibility === 'hidden');
};

await listen();
const address = server.address();
assert(address && typeof address !== 'string');
const base = `http://127.0.0.1:${address.port}`;

let browser;
try {
  browser = await chromium.launch({ headless: true });
  console.log(`Playwright ${playwrightVersion}; Chromium ${browser.version()}`);
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

  for (const path of pages) {
    await page.goto(base + path, { waitUntil: 'networkidle' });
    const dimensions = await assertPageWidth(page, path, 390);
    assert(
      dimensions.blocks.some((block) => block.scroll > block.client),
      `${path}: control code block no longer exercises local scrolling`,
    );
    await assertMobileMenu(page, path);
    console.log(`PASS narrow ${path} width=390 local-scroll=present menu=open-close`);
  }

  await page.setViewportSize({ width: 1440, height: 900 });
  for (const path of pages) {
    await page.goto(base + path, { waitUntil: 'networkidle' });
    await assertPageWidth(page, path, 1440);
    console.log(`PASS wide   ${path} width=1440 no-page-overflow`);
  }
} finally {
  await browser?.close();
  await closeServer();
}
