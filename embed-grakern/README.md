# embed-grakern — structural channel over grakern (JVM-only)

The `structural.*` channel of ADR 0001 rev 3 (§D4b, §D4c): a `PropositionChart` is reified into a
grakern directed labelled neighbourhood, Weisfeiler–Lehman subtree + optimal-assignment kernels
are compiled once over the source charts, and recall charts are aligned against that prepared
state through grakern's query overlay. The result is `d_wl = 1 − normalizedKernel`, exposed as an
`align.StructuralDistance`.

Structure **grades**; it never adjudicates. Admissibility of an (anchor, fidelity mode) pair comes
from the ModeGate in `align` (`ChartCompatibility`/`ContradictionDetector`), and this module cannot
change it — see `IntegrationSuite`.

## Why JVM-only

grakern's WL feature compiler (`engine-jvm`) depends on gale (JVM+JS only) and JVM concurrency.
Portable modules of storymodel4s never see a grakern or graph4s type; this project is a plain sbt
`project`, not a cross project, and is excluded from `allModules`. A portable WL emitter is an
open upstream bead in grakern; until it lands, the no-model portable tier is the hashed-n-gram /
TF-IDF baselines in `embed-core`.

## Reification (law G1 through the consumer)

- one vertex per concept, keyed `(frame id | lemma, kind, polarity-for-predicates)`;
- one vertex per relation, keyed `(source role spelling, normalized role)`, joined by `src`
  (predicate → relation) and `tgt` (relation → filler) arcs — explicit, directed source/target
  incidence, so an ARG0/ARG1 filler swap is non-isomorphic (proved by brute force in
  `ReificationSuite`);
- one vertex per embedded proposition, keyed by embedding kind, joined the same way;
- literal and unknown fillers become keyed leaves.

Charts are put in `Canonical.form` first, so vertex ids and enumeration order depend on structure
alone (law G2 holds by construction, on top of grakern's own invariance laws).

## Building

grakern has no published artifacts and, at the pinned revision, no git remote. The pin in
`build.sbt` (`grakernRevision`) is an immutable SHA; until grakern is pushed you **must** supply
the local checkout:

```
sbt -Dstorymodel4s.grakern.build=/path/to/grakern embedGrakern/test
# or: STORYMODEL4S_GRAKERN_BUILD=/path/to/grakern sbt embedGrakern/test
```

grakern's own build pins graph4s and gale by SHA from GitHub; the first load clones them. The
generated `GrakernPin.revision` and every `ProviderCall` receipt record the pin; the transitive
graph4s/gale SHAs are recorded as "via grakern@<sha>".

## API

- `ChartNeighbourhood.of(chart, key)` → `ReifiedChart` (sample + vertices + arcs).
- `StructuralProgram.of(rounds)` → the WL program with durable named codecs and a
  `ProviderFingerprint`.
- `PreparedSources.of(program, sources)` → immutable compiled source state; `kernelRow(query)`,
  `vectorOf(query)` (dense L2 vector over the prepared dictionary; bench use only — novel query
  colours are dropped).
- `StructuralSpaces.of(program, dictionarySize)` → the `structural.wl.grakern.r<rounds>`
  `EmbeddingSpace` identity.
- `GrakernStructuralDistance.prepare(sources, rounds = 2)` → `align.StructuralDistance` with
  memoized query rows and `receipts: Vector[ProviderCall]`.

## Deferred

Portable emitter and sparse per-row access (grakern beads); a text-payload `Embedder` instance
(charts are not text — the channel is consumed through `PropositionEvidence`, not `EmbedPayload`).
