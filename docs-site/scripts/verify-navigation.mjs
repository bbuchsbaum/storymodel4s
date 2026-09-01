import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { relative, resolve } from 'node:path';
import { sidebar } from '../src/lib/navigation.mjs';

const root = process.cwd();
const contentRoot = resolve(root, 'src/content/docs');

function walk(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name);
    return entry.isDirectory() ? walk(path) : [path];
  });
}

function slugFor(path) {
  const name = relative(contentRoot, path).replaceAll('\\', '/').replace(/\.mdx$/, '');
  return name === 'index' ? '' : name.replace(/\/index$/, '');
}

function pageFor(slug) {
  return slug === '' ? resolve(root, 'dist/index.html') : resolve(root, `dist/${slug}/index.html`);
}

const sourcePages = walk(contentRoot).filter((path) => path.endsWith('.mdx'));
const sourceSlugs = sourcePages.map(slugFor).sort();
const sidebarSlugs = sidebar.flatMap((group) => group.items.map((item) => item.slug));
const duplicateSlugs = sidebarSlugs.filter((slug, index) => sidebarSlugs.indexOf(slug) !== index);
const missingFromNavigation = sourceSlugs.filter((slug) => slug && !sidebarSlugs.includes(slug));
const missingSources = sidebarSlugs.filter((slug) => !sourceSlugs.includes(slug));

if (duplicateSlugs.length || missingFromNavigation.length || missingSources.length) {
  throw new Error(
    `navigation inventory mismatch\nduplicates: ${duplicateSlugs.join(', ') || 'none'}` +
      `\nunlisted pages: ${missingFromNavigation.join(', ') || 'none'}` +
      `\nmissing sources: ${missingSources.join(', ') || 'none'}`
  );
}

for (const slug of sourceSlugs) {
  if (!existsSync(pageFor(slug))) throw new Error(`built page missing for /${slug}`);
}

const internalLinks = new Set();
for (const path of sourcePages) {
  const source = readFileSync(path, 'utf8');
  for (const match of source.matchAll(/(?:href=|link:\s*|\]\()(["']?)(\/[a-z0-9][^"')\s#]*\/?)\1/g)) {
    internalLinks.add(match[2]);
  }
}

for (const link of internalLinks) {
  const slug = link.replace(/^\//, '').replace(/\/$/, '');
  if (!existsSync(pageFor(slug))) throw new Error(`internal link has no built page: ${link}`);
}

console.log(`verified ${sourceSlugs.length} built pages`);
console.log(`verified ${sidebarSlugs.length} unique sidebar entries`);
console.log(`verified ${internalLinks.size} internal links`);
