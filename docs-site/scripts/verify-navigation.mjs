import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
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

const siteOrigin = 'https://bbuchsbaum.github.io';
const siteBase = '/storymodel4s/';
const dist = resolve(root, 'dist');
const internalLinks = new Set();
for (const slug of sourceSlugs) {
  const page = pageFor(slug);
  const pageUrl = new URL(`${siteBase}${slug ? `${slug}/` : ''}`, siteOrigin);
  const rendered = readFileSync(page, 'utf8');
  for (const match of rendered.matchAll(/\b(?:href|src)="([^"]+)"/g)) {
    const target = new URL(match[1], pageUrl);
    if (target.origin !== siteOrigin) continue;
    if (target.pathname !== siteBase.slice(0, -1) && !target.pathname.startsWith(siteBase)) {
      throw new Error(`local URL escapes Pages base: ${target.pathname} in ${relative(root, page)}`);
    }
    const localPath = decodeURIComponent(target.pathname.slice(siteBase.length));
    const artifact = resolve(dist, localPath);
    if (artifact !== dist && !artifact.startsWith(`${dist}/`)) {
      throw new Error(`local URL escapes built directory: ${target.pathname}`);
    }
    const file = existsSync(artifact) && statSync(artifact).isFile()
      ? artifact
      : resolve(artifact, 'index.html');
    if (!existsSync(file) || !statSync(file).isFile()) {
      throw new Error(`local URL has no built artifact: ${target.pathname} in ${relative(root, page)}`);
    }
    internalLinks.add(target.pathname);
  }
}

console.log(`verified ${sourceSlugs.length} built pages`);
console.log(`verified ${sidebarSlugs.length} unique sidebar entries`);
console.log(`verified ${internalLinks.size} local page and resource URLs`);
console.log('verified rendered links retain the configured GitHub Pages base path');
