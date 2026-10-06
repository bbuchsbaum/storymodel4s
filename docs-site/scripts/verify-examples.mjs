import { readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { extractExampleOutput } from './sbt-output.mjs';

const siteRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = resolve(siteRoot, '..');
const manifestPath = resolve(siteRoot, 'examples/manifest.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));

if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.examples)) {
  throw new Error('examples/manifest.json must use schemaVersion 1 and contain an examples array');
}

function filesWithSuffix(directory, suffix) {
  return readdirSync(resolve(siteRoot, directory), { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(suffix))
    .map((entry) => `${directory}/${entry.name}`)
    .sort();
}

function compareInventory(label, actual, declared) {
  const omitted = actual.filter((path) => !declared.includes(path));
  const missing = declared.filter((path) => !actual.includes(path));
  if (omitted.length || missing.length) {
    throw new Error(
      `${label} inventory mismatch\nomitted from manifest: ${omitted.join(', ') || 'none'}` +
        `\nmissing from disk: ${missing.join(', ') || 'none'}`
    );
  }
}

const ids = manifest.examples.map((entry) => entry.id);
if (new Set(ids).size !== ids.length) throw new Error('example ids must be unique');

const sourceInventory = [
  ...filesWithSuffix('examples', '.scala'),
  ...filesWithSuffix('examples-grakern', '.scala'),
];
const outputInventory = [
  ...filesWithSuffix('examples', '.output.txt'),
  ...filesWithSuffix('examples-grakern', '.output.txt'),
];
compareInventory('Scala source', sourceInventory, manifest.examples.map((entry) => entry.source).sort());
compareInventory('recorded output', outputInventory, manifest.examples.map((entry) => entry.output).sort());

// STORYMODEL4S_GRAKERN_BUILD may name a local grakern checkout. When it is unset the build resolves
// grakern itself, from the revision pinned in build.sbt, by cloning it from GitHub. CI runs unset,
// so the replay there is bound to the pin and not to whatever a developer has checked out.
const grakernBuild = process.env.STORYMODEL4S_GRAKERN_BUILD;
const grakernOverride = grakernBuild ? [`-Dstorymodel4s.grakern.build=${grakernBuild}`] : [];

function quoteForSbt(path) {
  return path.replaceAll('\\', '\\\\').replaceAll('"', '\\"');
}

const byProject = Map.groupBy(manifest.examples, (entry) => entry.project);
for (const [project, entries] of byProject) {
  const sources = entries
    .map((entry) => `file("${quoteForSbt(resolve(siteRoot, entry.source))}")`)
    .join(', ');
  const args = [
    ...grakernOverride,
    '-Dsbt.supershell=false',
    '-Dsbt.log.noformat=true',
    `project ${project}`,
    `set Compile / unmanagedSources ++= Seq(${sources})`,
    ...entries.map((entry) => `runMain ${entry.main}`),
  ];
  const run = spawnSync('sbt', args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  const transcript = `${run.stdout ?? ''}${run.stderr ?? ''}`;
  if (run.status !== 0) {
    process.stderr.write(transcript);
    throw new Error(`sbt failed while replaying ${project} examples (exit ${run.status})`);
  }

  for (const entry of entries) {
    const actual = extractExampleOutput(transcript, entry.main);
    const expected = readFileSync(resolve(siteRoot, entry.output), 'utf8');
    if (actual !== expected) {
      let firstDifference = 0;
      while (
        firstDifference < actual.length &&
        firstDifference < expected.length &&
        actual[firstDifference] === expected[firstDifference]
      ) {
        firstDifference += 1;
      }
      throw new Error(
        `${entry.id} output drifted at byte ${firstDifference}; ` +
          `expected ${relative(repositoryRoot, resolve(siteRoot, entry.output))}`
      );
    }
    process.stdout.write(`verified ${entry.id}: ${Buffer.byteLength(actual)} output bytes\n`);
  }
}

process.stdout.write(`verified ${manifest.examples.length} executable documentation examples\n`);
