import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { alignmentRowsIdentity, parseAlignmentRows } from '../src/lib/alignment-data.mjs';
import { parseSummaryMetrics, summaryMetricsIdentity } from '../src/lib/summary-data.mjs';
import {
  interviewFigureLabels,
  interviewIdentity,
  parseInterviewOutput,
} from '../src/lib/interview-data.mjs';
import { parseStoryContextOutput, storyContextIdentity } from '../src/lib/story-context-data.mjs';

const root = process.cwd();
const outputPath = resolve(root, 'examples/align-and-interpret.output.txt');
const pagePath = resolve(root, 'dist/method/align-and-interpret/index.html');
const summaryOutputPath = resolve(root, 'examples/summarize-recall.output.txt');
const summaryPagePath = resolve(root, 'dist/method/summarize-recall/index.html');
const interviewOutputPath = resolve(root, 'examples/score-interview.output.txt');
const interviewPagePath = resolve(root, 'dist/method/score-interview/index.html');
const contextOutputPath = resolve(root, 'examples/model-a-story.output.txt');
const contextPagePath = resolve(root, 'dist/method/model-a-story/index.html');
const raw = readFileSync(outputPath, 'utf8');
const page = readFileSync(pagePath, 'utf8');
const summaryRaw = readFileSync(summaryOutputPath, 'utf8');
const summaryPage = readFileSync(summaryPagePath, 'utf8');
const interviewRaw = readFileSync(interviewOutputPath, 'utf8');
const interviewPage = readFileSync(interviewPagePath, 'utf8');
const contextRaw = readFileSync(contextOutputPath, 'utf8');
const contextPage = readFileSync(contextPagePath, 'utf8');
const rows = parseAlignmentRows(raw);
const identity = alignmentRowsIdentity(rows);
const digest = createHash('sha256').update(identity).digest('hex');

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
  console.log(`ok  ${message}`);
}

requireCondition(rows.length === 2, 'figure data contains two recall units');
requireCondition(
  page.includes('Source and external mass for each recall unit'),
  'rendered figure has an accessible title'
);
requireCondition(
  page.includes('data-unit="0" data-source="0.4608" data-external="0.5392"'),
  'rendered unit 0 matches captured output'
);
requireCondition(
  page.includes('data-unit="1" data-source="0.0209" data-external="0.9791"'),
  'rendered unit 1 matches captured output'
);

const inconsistent = raw.replace('source=0.4608', 'source=0.9900');
let rejectedInconsistent = false;
try {
  parseAlignmentRows(inconsistent);
} catch {
  rejectedInconsistent = true;
}
requireCondition(rejectedInconsistent, 'inconsistent source/external mutation is rejected');

const coherentMutation = raw
  .replace('source=0.4608', 'source=0.9900')
  .replace('external=0.5392', 'external=0.0100');
const mutatedDigest = createHash('sha256')
  .update(alignmentRowsIdentity(parseAlignmentRows(coherentMutation)))
  .digest('hex');
requireCondition(mutatedDigest !== digest, 'coherent value mutation changes figure-data identity');

const missingRow = raw
  .split('\n')
  .filter((line) => !line.startsWith('unit 1 ->'))
  .join('\n');
let rejectedMissingRow = false;
try {
  parseAlignmentRows(missingRow);
} catch {
  rejectedMissingRow = true;
}
requireCondition(rejectedMissingRow, 'missing alignment row is rejected');

console.log(`figure data SHA-256 ${digest}`);

const metrics = parseSummaryMetrics(summaryRaw);
const summaryIdentity = summaryMetricsIdentity(metrics);
const summaryDigest = createHash('sha256').update(summaryIdentity).digest('hex');
requireCondition(metrics.length === 7, 'summary figure data contains seven measures');
requireCondition(
  summaryPage.includes('Recall estimates and their support'),
  'rendered summary figure has an accessible title'
);
requireCondition(
  summaryPage.includes('data-metric="causal preservation" data-value="n/a" data-support="0.0000"'),
  'rendered causal preservation remains unavailable'
);
requireCondition(
  summaryPage.includes('data-metric="discourse chronology" data-value="0.2686" data-support="0.0082"'),
  'rendered chronology keeps its low support'
);

const fabricatedZero = summaryRaw.replace(
  'causal preservation: n/a',
  'causal preservation: 0.0000'
);
let rejectedFabricatedZero = false;
try {
  parseSummaryMetrics(fabricatedZero);
} catch {
  rejectedFabricatedZero = true;
}
requireCondition(rejectedFabricatedZero, 'numeric value with zero conditioning mass is rejected');

const changedSummary = summaryRaw.replace('fidelity: 0.5000', 'fidelity: 0.4000');
const changedSummaryDigest = createHash('sha256')
  .update(summaryMetricsIdentity(parseSummaryMetrics(changedSummary)))
  .digest('hex');
requireCondition(changedSummaryDigest !== summaryDigest, 'summary value mutation changes figure-data identity');

const missingMetric = summaryRaw
  .split('\n')
  .filter((line) => !line.startsWith('story-world chronology:'))
  .join('\n');
let rejectedMissingMetric = false;
try {
  parseSummaryMetrics(missingMetric);
} catch {
  rejectedMissingMetric = true;
}
requireCondition(rejectedMissingMetric, 'missing summary measure is rejected');

console.log(`summary figure data SHA-256 ${summaryDigest}`);

const context = parseStoryContextOutput(contextRaw);
const contextDigest = createHash('sha256')
  .update(storyContextIdentity(context))
  .digest('hex');
requireCondition(
  contextPage.includes(`Sentence ${context.sentence}: one passage, two contexts`),
  'rendered context figure names the shared source sentence'
);
requireCondition(
  contextPage.includes(
    `data-record="${context.root.id}" data-context="${context.root.context.id}" data-parent="none" data-predicate="${context.root.predicate}" data-polarity="${context.root.polarity}"`
  ),
  'rendered root-world record matches captured output'
);
requireCondition(
  contextPage.includes(
    `data-record="${context.nested.id}" data-context="${context.nested.context.id}" data-parent="${context.nested.context.parent}" data-predicate="${context.nested.predicate}" data-polarity="${context.nested.polarity}"`
  ),
  'rendered nested speech record matches captured output'
);
requireCondition(
  contextPage.includes(context.evidence),
  'rendered context figure carries the exact source sentence'
);

const brokenParent = contextRaw.replace(
  'context parent: wog:ctx:world',
  'context parent: wog:ctx:unrelated'
);
let rejectedBrokenParent = false;
try {
  parseStoryContextOutput(brokenParent);
} catch {
  rejectedBrokenParent = true;
}
requireCondition(rejectedBrokenParent, 'unrelated nested-context parent is rejected');

const changedContext = contextRaw.replace('polarity: Negative', 'polarity: Positive');
const changedContextDigest = createHash('sha256')
  .update(storyContextIdentity(parseStoryContextOutput(changedContext)))
  .digest('hex');
requireCondition(changedContextDigest !== contextDigest, 'context mutation changes figure-data identity');

console.log(`context figure data SHA-256 ${contextDigest}`);

const interview = parseInterviewOutput(interviewRaw);
const interviewLabels = interviewFigureLabels(interview);
const interviewDigest = createHash('sha256')
  .update(interviewIdentity(interview))
  .digest('hex');
requireCondition(interview.categories.length === 9, 'interview figure data contains nine categories');
requireCondition(
  interviewPage.includes(interviewLabels.title),
  'rendered interview figure has a data-derived accessible title'
);
requireCondition(
  interviewPage.includes('data-category="ExternalEvent" data-point="14.1900" data-low="13.5500" data-high="15.8700" data-hard="14"'),
  'rendered ExternalEvent row matches captured output'
);
requireCondition(
  interviewPage.includes('data-resolution="phase" data-resolved="0.6876" data-unresolved="0.3124" data-excluded="0.0000"'),
  'rendered resolution partition matches captured output'
);
requireCondition(
  [
    interviewLabels.resolution,
    interviewLabels.coverage,
    interviewLabels.profile,
    interviewLabels.density,
    interviewLabels.perceptual,
    interviewLabels.mental,
  ].every((label) => interviewPage.includes(label)),
  'every rendered interview summary label matches captured output'
);

const brokenInterval = interviewRaw.replace(
  'InternalEvent              point=4.8103 interval=[4.4503, 7.2103]',
  'InternalEvent              point=4.8103 interval=[4.4503, 4.7000]'
);
let rejectedBrokenInterval = false;
try {
  parseInterviewOutput(brokenInterval);
} catch {
  rejectedBrokenInterval = true;
}
requireCondition(rejectedBrokenInterval, 'point outside interview interval is rejected');

const changedResolution = interviewRaw
  .replace('resolved=0.6876', 'resolved=0.6000')
  .replace('unresolved=0.3124', 'unresolved=0.4000');
const changedInterviewDigest = createHash('sha256')
  .update(interviewIdentity(parseInterviewOutput(changedResolution)))
  .digest('hex');
requireCondition(changedInterviewDigest !== interviewDigest, 'resolution mutation changes figure-data identity');

const missingCategory = interviewRaw
  .split('\n')
  .filter((line) => !line.startsWith('Other '))
  .join('\n');
let rejectedMissingCategory = false;
try {
  parseInterviewOutput(missingCategory);
} catch {
  rejectedMissingCategory = true;
}
requireCondition(rejectedMissingCategory, 'missing interview category is rejected');

function renderInterviewMutation() {
  const courtRoot = mkdtempSync(resolve(root, '.figure-court-'));
  try {
    for (const path of ['astro.config.mjs', 'package.json', 'tsconfig.json', 'src', 'public', 'examples']) {
      cpSync(resolve(root, path), resolve(courtRoot, path), { recursive: true });
    }
    symlinkSync(resolve(root, 'node_modules'), resolve(courtRoot, 'node_modules'), 'dir');

    const mutatedOutput = interviewRaw
      .replace('resolved=0.6876', 'resolved=0.6000')
      .replace('unresolved=0.3124', 'unresolved=0.4000')
      .replace('event_purity=0.8750', 'event_purity=0.7500');
    const mutatedOutputPath = resolve(courtRoot, 'examples/score-interview.output.txt');
    writeFileSync(mutatedOutputPath, mutatedOutput);

    const mutatedDigest = createHash('sha256').update(mutatedOutput).digest('hex');
    const pageSourcePath = resolve(courtRoot, 'src/content/docs/method/score-interview.mdx');
    const pageSource = readFileSync(pageSourcePath, 'utf8').replace(
      /export const OUTPUT_SHA = '[0-9a-f]+';/,
      `export const OUTPUT_SHA = '${mutatedDigest}';`
    );
    writeFileSync(pageSourcePath, pageSource);

    const result = spawnSync(resolve(root, 'node_modules/.bin/astro'), ['build'], {
      cwd: courtRoot,
      encoding: 'utf8',
      env: process.env,
    });
    if (result.status !== 0) {
      throw new Error(`mutated interview render failed:\n${result.stdout}\n${result.stderr}`);
    }
    return readFileSync(resolve(courtRoot, 'dist/method/score-interview/index.html'), 'utf8');
  } finally {
    rmSync(courtRoot, { recursive: true, force: true });
  }
}

const mutatedInterviewPage = renderInterviewMutation();
requireCondition(
  mutatedInterviewPage.includes('resolved 0.6000 · unresolved 0.4000 · excluded 0.0000') &&
    mutatedInterviewPage.includes('event purity 0.7500 · probe gain 0.4286'),
  'coherent interview mutation changes reader-visible labels in a rendered page'
);

console.log(`interview figure data SHA-256 ${interviewDigest}`);
