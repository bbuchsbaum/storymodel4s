# MASC audit rescue: 21 September 2026

Mote: `bd-01M2TA2EMFCWRKXK6QJHGV702Q`.
Source: the unregistered clone `.worktrees/feature-values-dev`, clean, HEAD on
`audit/masc-role-corpus`, no stash. "Only copy" means only that the commit was absent
from main and from every remote-tracking ref in the clone. GitHub and other clones
were not searched.

## What was rescued

| Main-repo ref | Commit | Tree |
|---|---|---|
| `refs/heads/rescue/audit-masc-role-corpus` | `b756b4bb1aca375a14fd26d024e2b3a578ba8688` | `69b7c7c3b5f1729cdb6806fe974683e4200bfbe1` |

- Before the rescue, `git cat-file -t b756b4bb` failed in the main repo: its object
  database did not contain the commit.
- It was rescued by
  `git fetch --no-tags .worktrees/feature-values-dev refs/heads/audit/masc-role-corpus:refs/heads/rescue/audit-masc-role-corpus`,
  which exited 0.
- `git for-each-ref --contains b756b4bb` in the main repo now lists exactly that ref.
- `git rev-parse b756b4bb^{tree}` prints `69b7c7c3…` in both the main repo and the clone.
- The parent `680ef6e6` was already on `main`. The commit adds 12 objects: 1 commit,
  4 trees and 7 blobs, one blob for each file it touches. It adds 11,984 lines across
  four `docs/calibration/2026-09-04-masc-*` files, `tools/audit-masc.py` and
  `tools/test_audit_masc.py`, and 4 lines to `docs/calibration/participant-roles.md`.
  `git cat-file --batch-check` finds all 12 in the main repo.

## Reachability check of the clone

**The clone borrows main's object store.** Its `.git/objects/info/alternates` points
at the main repo's `.git/objects`, and its own store holds only 157 loose objects
(864 KB). Everything else it can see belongs to main. As a result, `git fsck` run in
the clone reports main's unreferenced commits as the clone's own.

| What was checked | Count | Result |
|---|---:|---|
| Local branches, notes ref and tag | 8 | 7 already reachable from a main ref; `audit/masc-role-corpus` was the only absent one and is now rescued |
| Stashes | 0 | — |
| Remote-tracking refs | 180 | 179 already reachable; `origin/solo/world-order-input` (`91333a46`) was present in main but unreferenced |
| Reflog commits | 15 | 14 reachable; the 15th was `b756b4bb` |
| Objects in the clone's own store | 157 | all 157 now present in main (`cat-file --batch-check`: 0 missing). Their commits are checked in the next row; the cold review also found every object reachable through main's refs (`rev-list --objects --all`) |
| Commits among those 157 | 11 | all 11 reachable from a main ref (`main`, `refs/notes/calibration-20260904` or the rescue ref) |
| `fsck --dangling` commits seen from the clone | 227 | all present in main. Before the salvage pins below, 51 were reachable and the other 176 were main's own unreferenced commits, visible through the alternate |
| `FETCH_HEAD`, `ORIG_HEAD` | 2 | `ad912012` and `3a6d73d8`, both reachable from main refs |

`91333a46` is an earlier version of `bbceb824`, the tip of main's
`solo/world-order-input`. Both have the same parent, author date and subject. Their
content differs (7 files, +93/−63), so the later commit is an amended version, not a
rebase. `91333a46` is preserved as `refs/heads/rescue/world-order-input-pre-amend`
and **listed for the owner** to keep or drop.

## Main's own unreferenced commits

These objects are not at risk from deleting the clone. They are listed here because
the clone sweep surfaced them.

The 176 are dangling *tips*. Counting their ancestors, `git fsck --unreachable
--no-reflogs` in main finds 258 commits that no ref reaches. For 244 of them, main's
reflogs are the only protection, and 14 have none. Main uses default gc settings,
under which reflog entries for unreachable commits expire 30 days after they were
written; after that, auto-gc can prune those commits. Most of the tips were authored
on 28–29 August, so expiry may begin in late September. The entry dates were not
read, and the reflog-protected commits were **not** audited individually.

Of the 176 tips, 165 are in a main reflog. The other 11 are not:

- 6 are patch-identical to commits on `main`.
- 2 are dropped `main` stashes from 17 September (`a1894c7a`, `7f57c30e`). Every file
  they touch is byte-identical to current `main`.
- `d736dc56` belongs to grakern, not storymodel4s: its tree is grakern's and its root
  is "Initialize grakern architecture and build". `../grakern` has it on `main` and
  `origin/main`, so it was not pinned here. (A first pass pinned it by mistake. That
  pin was removed after the cold review.)
- 2 have no commit with the same subject on `main`: `1fb0f9a8` (a WIP stash from
  28 August) and `b2f09ae3` ("codec: encode canonical typed addresses"). The subject
  check covered the tips only, and main's history was not searched for
  patch-equivalents. They are pinned under
  `refs/salvage/unreferenced-20260921/<sha8>`. The pins protect 3 commits from gc:
  the two tips plus the stash's index commit. Both are **listed for the owner**.

The cold review found no protection for 14 commits: the 8 unpinned tips above plus 6
of their ancestors. By its patch-id and blob checks, all 14 are redundant with main.
With the grakern pin removed, `d736dc56` and any of its ancestors that no reflog
covers join that unprotected set. They are safe in grakern's repository.

The clone's fsck also reports one dangling blob and one dangling tree. Both live in
main's store. The blob (`1e62b034`, 31 KB) is an 18 September draft of
`docs/refactor/TURNAROUND_PLAN.md`. Neither was pursued.

## What this does not establish

- **No off-machine copy exists.** Nothing was pushed. The rescue refs and the clone
  share one disk, which is 97% full.
- **The MASC audit is unreviewed and unmerged.** This preserves bytes. It is not a
  review, a merge or a rerun. The commit's own message says it carries Python
  evidence only.
- **Nothing was deleted.** Deleting the clone is `bd-01M2TA2TWN5QS8PT01VW7JX234`, and
  that ticket needs the owner's OK. Every clone ref is now covered by a main ref. The
  clone still reads main's object store, so if the main refs listed here are deleted
  and main is gc'd before the clone is removed, the clone can break.
