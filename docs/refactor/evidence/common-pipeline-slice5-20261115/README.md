# Common recorded pipeline: slice 5 (2026-11-15)

Ticket `bd-01M7E3G6TFG1YTWM9NTSZ4NRKX`. Runtime candidate
`1ce3c654ce55654c0fd21d292290aa3093f79f0e`, over recovery main
`e02d00f0a3355bec15f264e81c3a92cbde61a98f`.

Sherlock now exports its frozen development inputs into the existing generic study pipeline.
`read_bundle.py` reads both legacy v1 and common v2 bundles. Supplied unit numbers may start at
zero or have gaps. An omitted optional field stays absent; an explicit JSON null stays null.
The new synthetic CLI court exercises this with a recorded retry and two source scenes.

## Reproduce the migration

Run sbt in a standalone clone, not a linked worktree (AGENTS.md). Use absolute private input
paths. `PACKETS` is the frozen packet identity manifest, `UNITS` the admitted development unit
tables, and `EXPORT`, `RECORDINGS`, `BUNDLES` fresh output directories. These commands make no
model calls:

```text
sbt "pipeline/runMain storymodel4s.pipeline.agentmapping.agentMappingStudyExport ANNOTATION TRANSCRIPT_A TRANSCRIPT_B CAPTIONS PARTITION UNITS PACKETS opus EXPORT"
sbt "pipeline/runMain storymodel4s.pipeline.agentmapping.agentHarnessImport CACHE RECORDINGS"
sbt "pipeline/runMain storymodel4s.pipeline.agentmapping.agentStudyBundle EXPORT/source.csv EXPORT/parts.json EXPORT RECORDINGS claude-code-cli BUNDLES"
python3 tools/agentmapping/read_bundle.py BUNDLE
python3 tools/agentmapping/read_bundle.py BUNDLE --unit 0
python3 tools/agentmapping/compare_sherlock.py LEGACY_BUNDLES BUNDLES --receipt PRIVATE_RECEIPT.json
```

Quote individual paths inside the sbt command if they contain spaces. `agentMappingStudyExport`
refuses an existing output directory and any changed participant population, prompt, outline or
system identity. Its source CSV retains the checked row ordinals, scene assignments and exact
media ticks. `study_bundle.py replies BUNDLES OUTPUT` emits v2 scorer inputs; the FilmFestival
scorer also reads v2 bundle directories directly.

## Identity and claim boundary

The [compatibility decision](../../../plans/2026-11-10-common-recorded-pipeline-design.md#slice-5-compatibility-decision-2026-11-15)
uses the existing v2 contract. Generic CSV/clocks, supplied recall, policy and record identities
are deliberately different from legacy Sherlock identities. All 22 paired record digests
changed. The private crosswalk stores both versions' bundle hashes and record digests; the
[sanitized receipt](sherlock-comparison.json) binds that crosswalk without participant identifiers.
Compare predictions by participant and original unit number, never by changed content IDs.

This is engineering replay qualification on development artifacts. It establishes preservation
of recorded answers, missingness and retained self-reports. It does not validate scene labels,
referent accuracy, evidence citations, confidence calibration or generalization. The Python
reader checks answer/record consistency; canonical record integrity remains the Scala decoder's
responsibility. Self-reported confidence is not a calibrated score.

## Gate scope

`tools/reference-scope.sh BASE CANDIDATE` exits 5 because `AgentPacketCli.scala` contains
top-level main methods and no extractable type declaration. That refusal is retained. Manual
reference evidence finds the changed implementations in 13 Scala files, all in JVM-only
`pipeline`; no references occur in StoryAtlas sources. No portable module, build setting or
Atlas-consumed API changes. The explicit gate is `pipeline/test` with CI fatal warnings, then
`scalafmtCheckAll` and `scalafmtSbtCheck`, plus both Python tool test directories. Independent
review must confirm this scope before integration.

## Evidence

Private inputs, bundles and logs remain under ignored `data/.slice5/20261115/`. Public receipts
contain counts and hashes only. The authored third-study test regenerates its synthetic bundle
at `pipeline/target/synthetic-third-study.bundle.json` and needs no private corpus. The exact
commands, exit status and local evidence hashes are recorded in the qualification receipt.

Local qualification passed **587 Scala pipeline tests**, **53 agentmapping Python tests** and
**223 FilmFestival Python tests**, with zero failures or skips; both scalafmt checks passed.
The [Sherlock comparison](sherlock-comparison.json) covers 11 participants and 1,744 units with
zero prediction/extra differences. The [FilmFestival comparison](filmfestival-comparison.json)
covers 114 bundles and 19,356 unit rows across six conditions/models: every reply document
matches and all 12 score reports are byte-identical to the recovery baseline. Every replay
reports zero model calls.

The [qualification receipt](qualification.json) also binds the synthetic third-study output,
the exact manual scope, and two mutation controls: disabling the frozen-input guard fails its
named assertion (17 sibling tests pass); reinstating 1..n numbering fails the gapped-number
court (30 siblings pass). The compiled pre-fix extras projection fails the third-study assertion
with 42 siblings passing. Original sources were restored; these failing controls are not gates.
The [independent runtime review](review.json) is PASS with no blockers or follow-ups.

Hosted qualification is tracked separately on runtime SHA `1ce3c654c`:
[Continuous Integration](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43754885830) and
[Documentation](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43754885815).
Their status is not inferred from the local results.
