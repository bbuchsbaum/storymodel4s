import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

const repositoryRoot = resolve(process.cwd(), '..');

function git(...args: string[]): string {
  return execFileSync('git', args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
  }).trim();
}

export const sourceRevision =
  process.env.DOCS_SOURCE_REVISION?.trim() || git('rev-parse', 'HEAD');

export const sourceRevisionShort = sourceRevision.slice(0, 12);

export const generatedAt =
  process.env.DOCS_GENERATED_AT?.trim() || new Date().toISOString();

export const sourceBase = `https://github.com/bbuchsbaum/storymodel4s/blob/${sourceRevision}`;

export function sourceLink(path: string): string {
  return `${sourceBase}/${path}`;
}
