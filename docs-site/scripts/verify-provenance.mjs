#!/usr/bin/env node
/**
 * The tracked-mutation court, requested by codex-storymodel-collab.
 *
 * The receipt has been wrong twice, in opposite directions, and each time it LOOKED
 * fine. Reading the code did not catch either one; running it did.
 *
 *   1. DOCS_SOURCE_REVISION supplied the identity instead of confirming it. Setting
 *      it to forty zeroes built a site whose receipt attested to a revision that does
 *      not exist.
 *   2. The first fix set verified=true for a dirty tree and merely appended a warning.
 *      collab edited one tracked title without committing: the build still linked HEAD
 *      and every Confirmed block still claimed "directly inspectable at the cited
 *      source revision" against a revision that did not contain the edit.
 *   3. The second fix over-corrected -- a bare `git status --porcelain` counted 117
 *      untracked board-op files and reported the docs unverified permanently. A guard
 *      that always fires is as uninformative as one that never does.
 *
 * So this asserts BOTH directions. A court that only checks the failure is satisfied
 * by a receipt that never verifies anything, which is defect 3 all over again.
 *
 * Run from docs-site: node scripts/verify-provenance.mjs
 */
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const repoRoot = resolve(process.cwd(), "..");
const indexHtml = resolve(process.cwd(), "dist/index.html");
const mutantPage = resolve(
  process.cwd(),
  "src/content/docs/scala/getting-started.mdx",
);

let failures = 0;
const check = (name, ok, detail) => {
  console.log(
    `${ok ? "  ok  " : "  FAIL"} ${name}${detail ? ` — ${detail}` : ""}`,
  );
  if (!ok) failures += 1;
};

function build(env = {}) {
  try {
    execFileSync("npx", ["astro", "build"], {
      stdio: ["ignore", "ignore", "pipe"],
      env: { ...process.env, ...env },
    });
    return 0;
  } catch (e) {
    return e.status ?? 1;
  }
}

const read = () => readFileSync(indexHtml, "utf8");
const links = (h) =>
  new Set(h.match(/storymodel4s\/(?:tree|blob)\/[0-9a-f]{8}/g) ?? []).size;
const claims = (h) =>
  (h.match(/The cited revision contains the supporting source or receipt\./g) ?? [])
    .length;
const unverified = (h) => h.includes("no source revision");

// Refuse to run against a tree that is already dirty: the clean-tree assertions below
// would fail for a reason that has nothing to do with the code under test, and a red
// result nobody can attribute is worse than no result.
const trackedEdits = execFileSync(
  "git",
  ["status", "--porcelain", "--untracked-files=no"],
  {
    cwd: repoRoot,
    encoding: "utf8",
  },
);
// The precondition must use the SAME predicate as provenance.ts, or the court reports
// its own setup as a code defect. The first run did exactly that: this script was still
// untracked, an unignored untracked file inside docs-site counts as dirty, and all three
// clean-tree assertions failed with nothing wrong in the code they test -- five red lines
// describing the state of the checkout rather than the behaviour under test.
const siteAdditions = execFileSync("git", ["status", "--porcelain", "--", "docs-site"], {
  cwd: repoRoot,
  encoding: "utf8",
});
if (trackedEdits.trim().length > 0 || siteAdditions.trim().length > 0) {
  console.error("REFUSING TO RUN: this tree is dirty by the same predicate the receipt uses.");
  console.error(
    "The clean-tree assertions would fail for a reason unrelated to the code under test,",
  );
  console.error("and a red result nobody can attribute is worse than no result. Commit these:");
  console.error([trackedEdits, siteAdditions].filter((x) => x.trim()).join(""));
  process.exit(2);
}

console.log("provenance court");

// 1. Clean tree must PUBLISH a verified identity. Without this the suite passes
//    trivially on a receipt that has stopped verifying anything at all.
build();
let html = read();
check("clean tree publishes a source revision", !unverified(html));
check(
  "clean tree emits exact-revision links",
  links(html) > 0,
  `${links(html)} distinct`,
);
check(
  "clean tree keeps Confirmed claims",
  claims(html) > 0,
  `${claims(html)} claims`,
);

// 2. A tracked edit left uncommitted must withdraw all of it.
const original = readFileSync(mutantPage, "utf8");
try {
  writeFileSync(
    mutantPage,
    original.replace(/^title:.*$/m, "title: MUTATED WITHOUT COMMIT"),
  );
  const exit = build();
  html = read();
  check("tracked mutation still builds", exit === 0, `exit ${exit}`);
  check("tracked mutation withdraws the revision", unverified(html));
  check(
    "tracked mutation suppresses every source link",
    links(html) === 0,
    `${links(html)} left`,
  );
  check(
    "tracked mutation degrades Confirmed claims",
    claims(html) === 0,
    `${claims(html)} left`,
  );
} finally {
  writeFileSync(mutantPage, original);
}

// 3. Restoring must recover the verified state, or the guard is one-way and a single
//    stray edit would poison every later build.
build();
html = read();
check("restore recovers the revision", !unverified(html));
check(
  "restore recovers source links",
  links(html) > 0,
  `${links(html)} distinct`,
);

// 4. An asserted revision that disagrees with the derived one must fail the build.
const forty = "0".repeat(40);
check(
  "forty-zero DOCS_SOURCE_REVISION fails the build",
  build({ DOCS_SOURCE_REVISION: forty }) !== 0,
);

// 5. ...and one that AGREES must be accepted, so the variable stays usable for the
//    reproducible-build case it exists for.
const head = execFileSync("git", ["rev-parse", "HEAD"], {
  cwd: repoRoot,
  encoding: "utf8",
}).trim();
check(
  "matching DOCS_SOURCE_REVISION is accepted",
  build({ DOCS_SOURCE_REVISION: head }) === 0,
);

build();
console.log(
  failures === 0
    ? "\nprovenance court: all checks passed"
    : `\nprovenance court: ${failures} FAILED`,
);
process.exit(failures === 0 ? 0 : 1);
