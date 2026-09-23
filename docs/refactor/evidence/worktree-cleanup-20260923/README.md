# Two merged worktrees: cleanup proposal

Completed on 2026-09-23 after the owner approved both exact directories.

Removed only `.worktrees/own-the-metric` and `.worktrees/rootseg` using
`git worktree remove`, after repeating the clean/merged/ref/process checks.
Both were merged into main and clean. Observed available space increased by
6,455,234,560 bytes (6.01 GiB); concurrent filesystem activity can affect that delta.
`result.json` records the actual removals and final checks.
The independent review is Fray49 seq467 (`p1-cleanup-review`).

`proposal.json` records exact heads, size, preservation hashes and 14 archival
refs protecting old worktree-reflog commits. Branches and stashes remain in the
common Git directory. Copied HEAD reflogs and 9,332 old build log/report files
are preserved under ignored local `data/maintenance/worktree-cleanup-20260923/`.
Every archived file was SHA-256 verified by rereading the compressed archive.
Only content-free hashes/counts are committed; the retained logs stay local.

The ignored-file audit found build output, Python/Ruff caches and an identical
local worktree sbt shim. There are no tracked or untracked edits to preserve.
A successful whole-system `lsof` scan found no process using either worktree;
`process-audit.json` records the earlier check and `result.json` the repeated
successful check immediately before removal. Preserved archive/source hashes
were rechecked, and branch, stash and archival refs were identical after removal.
All other worktrees, clones, datasets and study outputs are excluded.
