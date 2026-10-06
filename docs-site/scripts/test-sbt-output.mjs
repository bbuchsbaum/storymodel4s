import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { extractExampleOutput } from './sbt-output.mjs';

test('colored hosted logger markers preserve exact plain example bytes', () => {
  const transcript = '\u001b[0m[\u001b[0minfo\u001b[0m] running docsprobe.inspectSource\u001b[0m\n' +
    'source = 3\n\n' + '\u001b[0m[\u001b[32msuccess\u001b[0m] Total time: 1 s\u001b[0m\n';
  assert.equal(extractExampleOutput(transcript, 'docsprobe.inspectSource'), 'source = 3\n\n');
});

test('ordinary and forked envelopes preserve empty and nonempty output', () => {
  assert.equal(extractExampleOutput('[info] running p.example\n[success] Total time: 0 s\n', 'p.example'), '');
  assert.equal(extractExampleOutput('[info] running (fork) p.example argument\n42\n[success] Total time: 1 s\n', 'p.example'), '42\n');
});

test('missing or different named run and missing completion refuse', () => {
  assert.throws(() => extractExampleOutput('[info] running pXexample\n42\n[success] Total time: 1 s\n', 'p.example'), /run marker/);
  assert.throws(() => extractExampleOutput('[info] running p.example\n42\n[success] fake payload\n', 'p.example'), /success marker/);
  assert.throws(() => extractExampleOutput('[info] running p.example\n42\n[info] running p.later\n7\n[success] Total time: 1 s\n', 'p.example'), /success marker/);
});

test('embedded success text remains example data', () => {
  const payload = 'value = [success] Total time: 1 s\n[success] example text\n';
  assert.equal(extractExampleOutput('[info] running p.example\n' + payload + '[success] Total time: 1 s\n', 'p.example'), payload);
});

test('payload control sequences remain observable output drift', () => {
  const payload = '\u001b[31m42\u001b[0m\n';
  const actual = extractExampleOutput('[info] running p.example\n' + payload + '[success] Total time: 1 s\n', 'p.example');
  assert.equal(actual, payload);
  assert.notEqual(actual, '42\n');
});
