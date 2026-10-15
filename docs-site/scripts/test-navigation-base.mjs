import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

const cases = [
  ['receipt-link', 'dist/index.html', '/storymodel4s/method/evidence/', '/storymodel4smethod/evidence/'],
  ['proof-frame', 'dist/method/evidence/index.html', '/storymodel4s/artifacts/verbatim-proof.html', '/storymodel4sartifacts/verbatim-proof.html'],
];

for (const [name, path, correct, malformed] of cases) {
  const original = readFileSync(path, 'utf8');
  assert(original.includes(`"${correct}"`), `${name}: positive fixture is missing`);
  try {
    writeFileSync(path, original.replace(`"${correct}"`, `"${malformed}"`));
    const run = spawnSync(process.execPath, ['scripts/verify-navigation.mjs'], { encoding: 'utf8' });
    assert.notEqual(run.status, 0, `${name}: malformed base path was accepted`);
    assert(run.stderr.includes(`local URL escapes Pages base: ${malformed}`),
      `${name}: refusal was not for the named malformed URL: ${run.stderr}`);
    console.log(`PASS ${name}: malformed base path refused`);
  } finally {
    writeFileSync(path, original);
  }
}
