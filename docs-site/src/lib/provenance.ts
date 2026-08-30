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

/**
 * An identity must be DERIVED from what it describes, never ASSERTED by the caller.
 *
 * The previous version read:
 *
 *     export const sourceRevision =
 *       process.env.DOCS_SOURCE_REVISION?.trim() || git('rev-parse', 'HEAD');
 *
 * which published whatever the environment claimed into `sourceRevision`, into every
 * `sourceLink`, and into the visible build receipt, without ever checking it against
 * the source actually being built. codex-storymodel-collab demonstrated the hole by
 * setting DOCS_SOURCE_REVISION to forty zeroes: the site rendered a receipt attesting
 * to a revision that does not exist, and nothing anywhere could tell.
 *
 * The test that matters is "if the caller lied here, what would catch it?" -- and the
 * answer was nothing. A receipt that cannot be wrong is not evidence; it is decoration
 * with the authority of evidence, which is worse than no receipt at all.
 *
 * So the environment variable no longer SUPPLIES the revision. It may only AGREE with
 * it. Where the revision cannot be derived, this module refuses to state one rather
 * than repeating a claim it cannot check -- the same discipline the library itself
 * applies when it publishes `n/a (conditioning weight 0.0000)` instead of inventing a
 * coverage number it has no support for.
 */

type Provenance =
  | { verified: true; revision: string; dirty: boolean }
  | { verified: false; reason: string; head?: string };

function deriveProvenance(): Provenance {
  let head: string;
  try {
    head = git('rev-parse', 'HEAD');
  } catch {
    // An exported tree (git archive) has no .git, which is a legitimate way to
    // build. It is NOT a licence to believe the environment instead.
    return {
      verified: false,
      reason: 'no git metadata in the build tree, so the source revision cannot be derived',
    };
  }

  const claimed = process.env.DOCS_SOURCE_REVISION?.trim();
  if (claimed && claimed !== head) {
    throw new Error(
      `DOCS_SOURCE_REVISION claims ${claimed} but the checked-out source is ${head}. ` +
        'This variable may only confirm the derived revision, never supply it. ' +
        'Refusing to publish a receipt for a revision that is not what was built.',
    );
  }

  // A dirty tree means HEAD does not describe the bytes that were built, so the
  // revision is NOT verified -- it is merely known. The first version of this fix
  // returned `verified: true` here and merely appended a warning to the receipt.
  // codex-storymodel-collab broke that in a disposable clone by editing one tracked
  // title without committing: the build still exited 0, still linked HEAD, and every
  // Evidence block still read "Confirmed - directly inspectable at the cited source
  // revision" while the cited revision demonstrably did not contain the mutation.
  //
  // A warning beside a link does not stop the link from asserting something false.
  // Dirty therefore fails the same way as no-git: no revision is published, so every
  // sourceLink returns null and the exact-revision evidence claims degrade with it.
  let dirty = false;
  try {
    dirty = git('status', '--porcelain').length > 0;
  } catch {
    return { verified: false, reason: 'could not determine whether the build tree is clean' };
  }

  if (dirty) {
    return {
      verified: false,
      reason: `the build tree has uncommitted changes, so HEAD (${head.slice(0, 12)}) does not describe what was built`,
      head,
    };
  }

  return { verified: true, revision: head, dirty: false };
}

const provenance = deriveProvenance();

export const sourceVerified = provenance.verified;

/** The revision actually built, or null when it could not be derived. Never a claim. */
export const sourceRevision = provenance.verified ? provenance.revision : null;

export const sourceRevisionShort = sourceRevision ? sourceRevision.slice(0, 12) : null;

/** The tree's HEAD when it is known but NOT the built identity. Never rendered as a claim. */
export const headRevisionUnverified = provenance.verified ? null : (provenance.head ?? null);

/**
 * What the receipt should SAY. Callers must render this rather than assuming a
 * revision exists, so an unverifiable build reads as unverifiable on the page
 * instead of silently omitting the caveat.
 */
export const sourceStatement = provenance.verified
  ? provenance.revision.slice(0, 12)
  : `source revision unverified: ${provenance.reason}`;

// DOCS_GENERATED_AT is deliberately still overridable. A timestamp is not an
// identity: it makes builds reproducible and it attests to nothing about the source.
export const generatedAt = process.env.DOCS_GENERATED_AT?.trim() || new Date().toISOString();

/**
 * Links are only meaningful against a revision we verified. With no verified
 * revision there is no honest URL to build, so callers get null and must omit the
 * link rather than point at a plausible-looking wrong one.
 */
export const sourceBase = sourceRevision
  ? `https://github.com/bbuchsbaum/storymodel4s/blob/${sourceRevision}`
  : null;

export function sourceLink(path: string): string | null {
  return sourceBase ? `${sourceBase}/${path}` : null;
}

/** Directory links need `tree`, not `blob`; a blob URL to a directory 404s on GitHub. */
export function sourceTreeLink(path: string): string | null {
  return sourceRevision
    ? `https://github.com/bbuchsbaum/storymodel4s/tree/${sourceRevision}/${path}`
    : null;
}
