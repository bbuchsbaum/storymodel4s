import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';

const requested = process.env.PLAYWRIGHT_PYTHON;
const candidates = [...new Set([requested, 'python3', 'python'].filter(Boolean))];

const python = candidates.find((candidate) => {
  const probe = spawnSync(
    candidate,
    ['-c', 'from playwright.sync_api import sync_playwright'],
    { stdio: 'ignore' },
  );
  return probe.status === 0;
});

if (!python) {
  console.error(
    'The layout court needs Python Playwright. Install it or set PLAYWRIGHT_PYTHON to a Python executable that can import playwright.sync_api.',
  );
  process.exit(1);
}

const court = spawnSync(python, [resolve('scripts/verify-mobile-layout.py')], {
  env: process.env,
  stdio: 'inherit',
});

if (court.error) throw court.error;
process.exit(court.status ?? 1);
