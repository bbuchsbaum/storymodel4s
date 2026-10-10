// Actual producer packets through the installed StoryAtlas browser host.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { pathToFileURL } = require('node:url');
const [consumer, fixtureDirectory, output] = process.argv.slice(2).map(x => path.resolve(x));
const modules = path.join(consumer, 'e2e/static');
const playwright = require(require.resolve('playwright', { paths: [modules] }));
assert.equal(require(require.resolve('playwright/package.json', { paths: [modules] })).version, '1.55.1');
const fixtures = JSON.parse(fs.readFileSync(path.join(fixtureDirectory, 'workspace-fixtures.json'), 'utf8'));
fs.mkdirSync(output, { recursive: true });
(async () => {
  let browser, context;
  const checks = [];
  try {
    browser = await playwright.chromium.launch({ headless: true });
    context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    await page.goto(pathToFileURL(path.join(consumer, 'target/edition/index.html')).href);
    await page.locator('#workspace-open').waitFor();
    const cases = ['text', 'timed'].flatMap(kind => fixtures[kind + 'Cohort'].map((packet, index) => ({ kind: kind + '-' + index, packet })));
    for (const { kind, packet } of cases) {
      const packetBytes = fs.readFileSync(packet, 'utf8').trim();
      const packetSha256 = createHash('sha256').update(packetBytes).digest('hex');
      const archive = JSON.parse(packetBytes);
      const mappingDigests = archive.entries.filter(entry => entry.role.kind === 'Mapping')
        .map(entry => entry.disposition.artifact.checksum);
      assert.equal(mappingDigests.length, 2);
      await page.locator('#workspace-open').setInputFiles(packet);
      await page.waitForFunction(expected => {
        const receipt = document.querySelector('#surface-workspace .workspace-receipts p');
        return receipt && expected.some(digest => receipt.textContent.includes(digest));
      }, mappingDigests);
      const panel = page.locator('#surface-workspace');
      await panel.getByRole('heading', { name: 'Source + recall', exact: true }).waitFor();
      assert.match(await panel.locator('.joined-source').innerText(), /Anna arrived\./);
      assert.match(await panel.locator('.joined-source').innerText(), /Bob left\./);
      assert.match(await panel.locator('.joined-recall').innerText(), /Anna arrived\./);
      await panel.getByRole('button', { name: 'Reference', exact: true }).click();
      assert.match(await panel.locator('.workspace-inspector').innerText(), /Normalized score mass/);
      await panel.getByRole('button', { name: 'Reconstruction', exact: true }).click();
      assert.match(await panel.locator('.workspace-inspector').innerText(), /Model posterior/);
      await panel.locator('.joined-recall button').first().click();
      await panel.locator('.joined-source button').first().click();
      assert.equal(await panel.locator('.joined-recall button[aria-pressed=true]').count(), 1);
      assert.equal(await panel.locator('.joined-source button[aria-pressed=true]').count(), 1);
      const downloadPromise = page.waitForEvent('download');
      await panel.getByRole('button', { name: 'Save view', exact: true }).click();
      const download = await downloadPromise;
      const saved = path.join(output, `${kind}-saved-view.json`);
      await download.saveAs(saved);
      const state = JSON.parse(fs.readFileSync(saved, 'utf8'));
      assert.equal(state.schemaVersion, 'surface-workspace-selection/v1');
      assert.equal(state.policy, 'reconstruction');
      assert.equal(state.workspace, packetSha256);
      assert.ok(state.recall && state.source);
      await panel.getByRole('button', { name: 'Clear selection', exact: true }).click();
      assert.equal(await panel.locator('.joined-recall button[aria-pressed=true]').count(), 0);
      await page.locator('#workspace-open').setInputFiles([packet, saved]);
      await panel.locator('.joined-recall button[aria-pressed=true]').waitFor();
      assert.equal(await panel.locator('.joined-source button[aria-pressed=true]').count(), 1);
      assert.equal(await panel.getByRole('button', { name: 'Reconstruction', exact: true }).getAttribute('aria-pressed'), 'true');
      await page.screenshot({ path: path.join(output, `${kind}-desktop.png`), fullPage: true });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.screenshot({ path: path.join(output, `${kind}-mobile.png`), fullPage: true });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true);
      await page.setViewportSize({ width: 1440, height: 1000 });
      checks.push({ kind, packetSha256, opened: true, policies: 2, exactSelectionsRestored: true });
    }
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(output, 'receipt.json'), JSON.stringify({ producerRevision: fixtures.producerRevision, checks, errors }, null, 2) + '\n');
    console.log(JSON.stringify({ checks, errors }));
  } finally {
    if (context) await context.close();
    if (browser) await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
