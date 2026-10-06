# Portable development and private data

Use one primary writer and, when useful, one reviewer. Mote owns tickets and narrow path
reservations. Work on one small acceptance criterion, commit it, qualify the exact SHA and push
the reviewed result. To change machines, release the claim, push, then pull and acquire the claim
on the next machine. Keep local `.mote/local/`, `.mote/tmp/`, `.agent-work/` and data out of Git.
Do not copy credentials or local actor configuration between machines.

## Public code on any development machine

Clone `https://github.com/bbuchsbaum/storymodel4s.git`. StoryAtlas is a separate checkout at
`https://github.com/bbuchsbaum/storyatlas4s.git`; its `build.sbt` records the producer revision.
Use normal machine-local GitHub authentication for writes. Repository SSH aliases are local
configuration; HTTPS cloning does not require the original workstation's aliases or credentials.

Install a supported JDK (the buc-gw01 checkpoint uses 21), sbt 1.12.14, Node 24, Python 3.9 or newer,
Clang/Xcode command-line tools and pkg-config for Native. The build selects Scala 3.7.4.
Mote must support the repository's operations; run `mote doctor` in the fresh checkout. Source
dependencies are pinned in the builds and fetched from their public remotes. Local overrides are
for coordinated changes and must be bound to actual clean SHAs in the qualification receipt.

```sh
sbt -batch 'set ThisBuild / tlFatalWarnings := true' checkAll githubWorkflowCheck
python3 -m unittest discover -s tools -p test_data_handoff.py
# In StoryAtlas, with its declared producer and Intaglio pins:
sbt -batch 'set ThisBuild / tlFatalWarnings := true' compileAll testAll app/fastLinkJS \
  scalafmtCheckAll scalafmtSbtCheck githubWorkflowCheck
```

Gate in a standalone clean clone. Linked worktrees are suitable for editing, but this build's
JGit integration does not support gating them. Keep the command, exact source/dependency SHAs,
actual exit status, passed/failed/skipped totals and full public-data log. Run one heavy gate at
a time. A local pass and a hosted CI pass are separate evidence. Synthetic/public fixtures can
run anywhere; private reader and scientific jobs require an approved machine and corpus use.

## Private data on approved machines

`docs/data/development-hosts.json` records owner-approved placement. It currently approves
`buc-gw01`, using its previously known SSH host key and actual machine identity `BUC-GW01`.
The existing source is `BUC-GW02`. Tailnet membership does not confer approval. Add a new
destination only after the owner approves it; record its actual machine identity, authenticated
host-key alias and canonical private root in a reviewed commit. Do not accept an unknown SSH key
automatically. The tool reads policy and its own source from committed `HEAD`, refusing a modified
tool before transport. No arbitrary destination/root override is supported for remote writes.

On buc-gw01 set:

```sh
export STORYMODEL4S_DATA="$HOME/.local/share/storymodel4s/data"
```

Keep sources and derived artifacts on approved private storage. Consult each `docs/data/<corpus>`
record and the existing study partition. Machine approval permits placement; it does not authorize
a new corpus use, redistribution, participant identification, or opening a sealed held-out split.

Create a private selection JSON outside Git. It has `assets`, an explicit list of relative file
paths, and `restrictions`, a list of existing committed `docs/data/` or `docs/plans/` references.
Create a separate private basis file describing the permitted development subset and how it was
selected against the existing partition. Do not select entire directories or held-out assets.
The tool retains source byte sizes/hashes, reference digests and the selection-basis digest in a
private manifest. Inspect the selection locally; do not paste its identifying filenames or prose
into chat, public logs or CI.

```sh
python3 tools/data_handoff.py plan --selection /private/path/selection.json \
  --basis /private/path/selection-basis.json
# Use the returned digest. Preview checks conflicts and resumable prefixes; no asset bytes move.
python3 tools/data_handoff.py transfer --manifest DIGEST
python3 tools/data_handoff.py transfer --manifest DIGEST --execute
python3 tools/data_handoff.py verify --manifest DIGEST
```

Transfer uses authenticated SSH byte streams, with strict existing-host-key checking. It checks
the actual remote machine before transmitting the private manifest. Partial files remain under
the private root's `.handoff/staging/`; repeating the same command resumes matching prefixes.
Conflicting final bytes, corrupt prefixes, symlinks and foreign metadata are refused. It neither
replaces unrelated files nor deletes source assets. Files are created private, verified in full,
promoted without replacement and made read-only. A per-root lock serializes handoffs.

Multi-file promotion can be interrupted. Some verified files may then exist without an admitted
dataset. The ready marker is written last, atomically, binding manifest digest, actual machine,
canonical root and verification counts. Before any permitted reader on the destination, require
this check to succeed; it checks the marker and rehashes every selected asset:

```sh
python3 tools/data_handoff.py check-local --manifest DIGEST
# Only after success, run the reader permitted by the selected corpus/partition.
```

The private manifest remains in destination staging for this check. A directory-presence check
or a stale marker is insufficient. Re-run `check-local` before a new reader job. Keep private
reader output and diagnostics outside Git; public receipts contain only reviewed counts, byte
verification status and digests. Failed transfer messages intentionally omit private paths and
payloads. Resolve a refused conflict locally; do not delete or overwrite it automatically.

The checkpoint evidence is under `docs/refactor/evidence/workstation-checkpoint-20261006/`.
Its receipts distinguish producer, consumer, hosted CI, workstation and private-reader results.
