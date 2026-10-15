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

const rootBypassingLinks = [];
for (const slug of sourceSlugs) {
  const page = pageFor(slug);
  const rendered = readFileSync(page, 'utf8');
  for (const match of rendered.matchAll(/(?:href|src)="\/(?:method|scala|reference|artifacts)\//g)) {
    rootBypassingLinks.push(`${relative(root, page)}: ${match[0]}`);
  }
}

if (rootBypassingLinks.length) {
  throw new Error(
    `site-relative link bypasses the configured GitHub Pages base path:\n${rootBypassingLinks.join('\n')}`,
  );
}

const siteOrigin = 'https://bbuchsbaum.github.io';
const siteBase = '/storymodel4s/';
const internalLinks = new Set();
for (const slug of sourceSlugs) {
  const page = pageFor(slug);
  const pageUrl = new URL(`${siteBase}${slug ? `${slug}/` : ''}`, siteOrigin);
  const rendered = readFileSync(page, 'utf8');
  for (const match of rendered.matchAll(/<a\b[^>]*\bhref="([^"]+)"/g)) {
    const target = new URL(match[1], pageUrl);
    if (target.origin === siteOrigin && target.pathname.startsWith(siteBase)) {
      internalLinks.add(target.pathname);
    }
  }
}

for (const link of internalLinks) {
  const slug = link.slice(siteBase.length).replace(/\/$/, '');
  if (!existsSync(pageFor(slug))) throw new Error(`internal link has no built page: ${link}`);
}

console.log(`verified ${sourceSlugs.length} built pages`);
console.log(`verified ${sidebarSlugs.length} unique sidebar entries`);
console.log(`verified ${internalLinks.size} internal links`);
console.log('verified rendered links retain the configured GitHub Pages base path');
