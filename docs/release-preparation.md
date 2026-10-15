# Release preparation

The installed CLI and executable guide are qualified local delivery artifacts. The current
publication gate is `bd-01M2TAMJ95K9D7H248A8387YW4`; the infrastructure ticket is
`bd-01M2TAKTB61XNXV4Y6A9J3CB0C`. This checklist records preparation for that gate. No stable
artifact, tag or deployed documentation site follows from a local build.

The current Native platform prerequisite is `bd-01M4YP83C7925M22V0K5AQ88BX`. Native 0.5.12
on macOS arm64 reproducibly crashes when hashing a literal NUL string, including without any
StoryModel type. Both Clang 15 and 20 reproduce it. Constructing the identical character at runtime
preserves the full identity-test coverage and passes, but does not repair that compiler/runtime
defect. [Retained controls](refactor/evidence/release-freeze-20261014/native-diagnostic/receipt.json)
keep this separate from the application repairs. Qualify the underlying behavior before
unrestricted stable Native publication.

## Reproducible checks

Run the full producer and pinned StoryAtlas consumer gates in clean standalone checkouts,
with fatal warnings, one heavy build at a time, and retained passed/failed/skipped totals.
A failed compilation makes that invocation inconclusive; preserve it and obtain a complete
passing invocation after the repair. Execute `npm run verify` from `docs-site`, and retain the guide's independent reader/browser receipts.

Build `pipeline/cliDistribution`, verify every manifest entry after extraction, and execute the
installed launcher outside the checkout. The manifest records the exact source commit, Scala
version, dependency bytes and ordered runtime classpath. Supply external model/media resources
separately. The local bundle includes its dependencies; it does not prove that a Maven consumer
can resolve every project POM.

Run `makePom` for each of the 48 portable module/platform pairs and eight JVM-only modules.
Record coordinates, packaging, licenses, developers, SCM, release version and every dependency.
Check dependency resolution without source-build overrides or a prepopulated local Maven
repository. In particular, the grakern/gale/graph4s source chain must have resolvable published
coordinates before dependent Maven artifacts can be offered. The artifact preflight receipt
records the observed result; unresolved coordinates are publication blockers.

Audit the exact installed distribution with:

```sh
STABILITY_DIST=/absolute/path/to/installed/storymodel4s-VERSION \
STABILITY_SOURCE=FULL_SOURCE_SHA \
  bash tools/stability-audit/verify.sh /absolute/path/to/semantic-inventory.json
```

The tool verifies distribution checksums and source identity before reading TASTy, and writes
an inventory provenance sidecar. The imported temporal inspection closure has its own
`tools/stability-audit/inspection-roots.txt`; enumerating it does not promote an experimental
API to stable. Signature reachability and constructor judgment remain separate obligations.
The owner-approved family table in `docs/api-stability.md` governs final boundary decisions.

## Compatibility rehearsal and baseline activation

```sh
bash tools/compatibility-rehearsal/run.sh /absolute/path/to/new-evidence-directory
```

This nonpublishing rehearsal uses the cached, pinned Scala 3.7.4 compiler and TASTy MiMa 1.4.0.
It compiles unchanged and deliberately incompatible contracts, requires the unchanged comparison
to pass, and requires exactly `MissingTermMember rehearsal.StableApi.keep` for the removed-method
comparison. No exclusion filter is installed. Raw command logs and dependency hashes are retained.
Explicit Scala libraries and the JDK base module are analyzer inputs as well as runner dependencies;
missing them caused the retained initial analyzer failure.

The release candidate now adds `1.0.0` to `tlMimaPreviousVersions` for subsequent 1.x versions.
`releaseBaselineCheck` runs before each published module's actual `mimaReportBinaryIssues`
task and requires that same module's exact `1.0.0` coordinate. An empty override or another
module's artifact cannot silently replace the comparison. The first `1.0.0` remains exempt.

The actual-task rehearsal is:

```sh
python3 tools/release/rehearse-baseline.py \
  --checkout /absolute/path/to/clean-standalone-candidate \
  --output /absolute/path/to/new-evidence-directory
```

It uses the real `coreJVM` build/release tasks with a synthetic class selected by sbt session
settings. A local file repository holds the synthetic baseline. The unchanged binary passes;
removing `releasebaselineprobe.StableApi.keep` fails MiMa; empty baselines for `1.0.1` and
`1.1.0`, and a wrong-module baseline, fail the guard. These six controls passed in the release
prerequisite candidate. No synthetic artifact is a released library baseline. Rebuild ordinary
source outputs after this rehearsal before generating a library distribution.

Experimental exclusions must be explicit and reviewed; no MiMa exclusion filter is installed.
The exact-member source-signature comparison separately demonstrates an allowed experimental
factory rename and a refused supported-accessor removal. Neither local control establishes
whole-library JavaScript/Native, wire or scientific compatibility. After the first stable
artifacts are published, the configured comparisons must resolve those actual artifact bytes.

## Current local rehearsal

The [14 October evidence](refactor/evidence/release-freeze-20261014/README.md) records the exact
`63a952f9` compiled candidate and its full hosted matrix, documentation and pinned Atlas checks.
The proposed upstream chain passed 1,006 source tests and staged nine JVM artifacts. Its recorded
release overlays supply fixed versions, local publication routing and homepage/SCM metadata from
the pinned repository URLs. A fresh binary consumer resolved those exact staged JARs and passed
six graph/kernel/query assertions. All 56 actual StoryModel POMs passed metadata and internal
closure checks. Fresh Maven Central resolution passed for 46 of 50 external coordinates. The four missing
`io.github.canardlapin` roots at `0.1.0-RC1` are `grakern-core_3`, `grakern-engine-jvm_3`,
`grakern-graph4s_3` and `grakern-standard_3`; their publication remains required.
[Complete receipt](refactor/evidence/release-freeze-20261014/publication/summary.json).

The preflight admits fixed Scala Native versions such as `3.7.4+0.5.12`; it still refuses
snapshots, ranges and wildcard-plus selectors before resolution. Eleven version controls and
a compiling guard mutation distinguish those cases, alongside actual Coursier resolution.

## Publication decision

The organization is already `io.github.bbuchsbaum`. Before publication, verify ownership of
that Sonatype namespace and the configured credential/signing route without exposing secrets.
The build currently disables release branches and tags. Activate publishing only after the
artifact matrix, dependency availability, final stability acceptance and exact revision gates
are complete and the owner authorizes publication. Record the resulting coordinates and bytes;
then activate the baseline for subsequent releases.

The documentation workflow currently verifies the site. Record and approve its hosting
destination before adding deployment, then verify the published URL against the approved
revision. Main synchronization, a Maven release, and site deployment are separate operations.
