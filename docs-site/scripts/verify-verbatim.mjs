import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const source = resolve('public/artifacts/verbatim-proof.html');
const built = resolve('dist/artifacts/verbatim-proof.html');

const [sourceBytes, builtBytes] = await Promise.all([readFile(source), readFile(built)]);

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

const result = {
  source: 'public/artifacts/verbatim-proof.html',
  built: 'dist/artifacts/verbatim-proof.html',
  sourceBytes: sourceBytes.length,
  builtBytes: builtBytes.length,
  sourceSha256: sha256(sourceBytes),
  builtSha256: sha256(builtBytes),
  byteIdentical: sourceBytes.equals(builtBytes),
};

console.log(JSON.stringify(result, null, 2));

if (!result.byteIdentical) {
  throw new Error('Astro changed the self-contained HTML artifact during the build.');
}
