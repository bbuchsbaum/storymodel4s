# Checked source/recall packet: producer qualification

The producer implementation at `f4af92e8fccf805f34a23d48ca1e9c08d084df96`
passed the clean `sbt -batch checkAll` gate: **8,230 total, 8,225 passed,
5 optional live tests skipped, zero failures/errors**, over 56 test tasks.
All 756 JUnit reports are fresh and their totals independently agree with the
command summaries. See [qualification.json](qualification.json) and
[checkAll.json](checkAll.json); the latter binds the command, source, raw log,
reports and compressed archive by hash. The local Clang 15 advisory is retained.

After that gate, primary `408178ef809442022868792380ba9b8d08fcf673` was merged
at `b86eae616437da8a61f64591e535ca4b0f3c8f53`. Its entire difference from the
gated main base is 302 `.mote/ops` files. Executable sources, tests and build are
unchanged. This evidence-only commit then preserves the generated handoff files.

## Consumer entry point

The [fixture index](fixtures/index.json) binds 21 producer-written artifacts from
the qualified source. `wog.workspace.json` and `bell.workspace.json` open through
`WorkspaceCodecs.decode`; their source, recall, inventory, mappings, derivation,
capabilities, optional presentation clocks and receipts are checked together.
Use these exact files for external consumer tests; do not reconstruct the fixtures
inside the consumer or copy narrative text into its application sources.

Both packets expose every inventory ordinal, a fixed target dictionary, two
separately supplied authored policies, exact discontiguous evidence, inverse
references and deterministic selected-record exports. A third policy retains an
actual local lexical HSMM execution with historical reconstruction authority.
Bell has three explicit chart abstentions and declared synthetic independent
presentation clocks. WOG has no word-clock sidecar and retains all four units
with `ClocksNotSupplied` dispositions. Bell's lawful Voyage projection includes
both plotted and untimed units, uses the full three-target source timeline,
and bridges actual emitted mark addresses back to qualified workspace identities.

`WorkspaceVoyage` refuses unsupported decisions, missing provenance and clocks
that cannot be represented exactly by the legacy seconds contract. A global
external winner is never silently replaced with a source winner. Posterior
fidelity states remain separate, and no value is renormalized by the adapter.
The presentation declares that recording correspondence is unestablished.

## Executed falsifiers

The independent Python reader imports no producer code. Its final run checks
514 expectations against literal expected answers, exact artifact/receipt hashes,
permission and identity bindings, unchanged measure values, exact evidence,
CSV coordinates, clock origins, rational observations and Voyage posteriors.
Four re-signed export mutations fail: duplicate CSV coordinates, wrong policy,
source-only renormalization and evidence hulling. The unmodified before/after
controls both pass. See [the final readback log](readback-mutations-f4af92e8.log).

Earlier compiled mutation witnesses cover byte-check bypass, filtering failed
rows, missing-as-zero, source-only renormalization, evidence hulling, ignored
export permission, serialization of denied packets and local-only identity.
Adapter witnesses cover undeclared clock origin, dropped source-address bridge
and rounded rational word timing. Exact commands, source revisions, test totals
and failed/restored runs are preserved in `development-evidence.tar.gz`.

The history is retained honestly: the first export-permission mutant survived
because a separate archive export guard still enforced denial; the corrected
boundary court kills it. Two adapter-harness attempts stopped on stale mutation
markers after passing controls; these are not counted as passed mutation courts.
The first full gate timed out in the first Native Voyage test while constructing
both shared HSMM fixtures. Moving that construction to suite setup retained the
default per-test timeout and all assertions; the focused nine-test Native suite
and the final full gate pass. The failed gate remains in the development archive.

Separate SD6 reviews examined byte admission, permission boundaries, semantic
joining, long identifiers, exact evidence, export readback and the lawful Voyage
adapter. Findings were repaired and tested before this qualification. Atlas's
own JVM/JS consumers and live end-to-end browser journey remain separate gates.

## Reproduction

Run from the qualified source with exact grakern checkout
`0329c43c88a0b71e9aa4456723bb16bac2fa3841`:

```sh
sbt -batch -Dstorymodel4s.grakern.build=/path/to/exact-grakern checkAll
sbt -batch -Dstorymodel4s.grakern.build=/path/to/exact-grakern \
  "fixturesJVM/runMain storymodel4s.fixtures.writeWorkspaceFixtures /tmp/m1-fixtures f4af92e8fccf805f34a23d48ca1e9c08d084df96"
python3 tools/check-workspace-fixtures.py /tmp/m1-fixtures f4af92e8fccf805f34a23d48ca1e9c08d084df96
python3 tools/mutate-workspace-readback.py /tmp/m1-fixtures f4af92e8fccf805f34a23d48ca1e9c08d084df96
```

The archived command receipts retain the actual isolated checkout and staging
paths used here. This is local producer qualification, not hosted CI, release,
calibration, automatic narrative/chart construction, verified media admission,
or completed M1. StoryAtlas must qualify the exact provider/consumer pair and
complete joined loading, shared inspection, replay/export and browser acceptance.
