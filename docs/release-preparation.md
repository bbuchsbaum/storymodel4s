# Release preparation

The installed CLI and executable guide are qualified local delivery artifacts. The current
publication gate is `bd-01M2TAMJ95K9D7H248A8387YW4`; the infrastructure ticket is
`bd-01M2TAKTB61XNXV4Y6A9J3CB0C`. This checklist records preparation for that gate. No stable
artifact or tag follows from a local build. The documentation site has its own completed
deployment receipt below.

## Source build and offline CLI

The current delivery route keeps grakern at GitHub revision
`0329c43c88a0b71e9aa4456723bb16bac2fa3841`, with graph4s
`ea5d2d762f85f5a0f97ee188deb5fac0ef2bcbaf` and Gale
`d55fe2f97196a76ab7879e1a12f1e92403aeba06`. The complete recipe is the source/CLI
qualifier below. It requires GitHub read access to those sources, Git, sbt, Python 3.11 or
newer, and an explicit JDK. Configured Git transport rewrites may supply that access; the
immutable revision checks still apply. Source-build network access and installed CLI execution
are separate stages.

Start from a checkout containing the reviewed commit. Choose a new output directory on a
volume with at least 15 GiB free. This recipe uses JDK 25, with which the formatter diagnostic
passes. A formatter dependency has a JDK 22-specific multi-release JAR defect: its version-22
class requires Java 23. JDK 17/21 select its Java 8 base class; their CI matrix stays intact.
The installed command bundle
requires Java 17 or newer.

```sh
python3 tools/release/qualify-source-cli.py \
  --source /absolute/path/to/storymodel4s \
  --revision FULL_REVIEWED_COMMIT_SHA \
  --java-home /absolute/path/to/jdk-25/Contents/Home \
  --output /absolute/path/on/build-volume/new-qualification
```

The qualifier clones that exact commit, uses fresh sbt boot/global/staging/Ivy/Coursier
directories, refuses ambient source/JVM overrides, and runs the JVM tests with fatal warnings
before packaging `pipeline/cliDistribution`. Add `--full-gate` for workflow generation and
the complete JVM/JavaScript/Native gate; this also needs Node and the Native C/C++ toolchain.
The task uses a short private IPC directory for sbt while heavy temporary files stay on the
build volume. Reusing the same output path refuses so a failed receipt cannot be overwritten.

The ZIP is under `checkout/pipeline/target/cli-distribution/` inside the qualification
directory. `installed/` contains its verified extraction. Keep `lib`, `classpath.txt`,
`manifest.json`, `dependency-provenance.json` and `notices/` together. The provenance identifies
each runtime JAR through actual sbt project products or external resolution records and joins
it to the packaged hash. Source products record their project and exact Git revision;
external products record their Maven and artifact identities, declared licenses and notice
evidence. Unknown origins or unresolved license evidence refuse packaging before an old
staging directory is replaced.

The qualifier copies the independent readers into a separate working directory, exercises the
paired exchange and weights `0/1,1/2,1/1`, then repeats them under macOS `sandbox-exec` with
network access denied. A live loopback control must connect before sandboxing and receive a
permission refusal inside it. Both readers must check the expected observation and members;
online/offline output bytes and exact resume must agree, and overwrite must refuse. A different
host needs an equally measured network-denial backend; a configuration flag alone is insufficient.
`receipt.json` retains literal commands, exits, log hashes, fetched revisions, test totals and
archive/manifest hashes, including any failure. Model weights and media tools remain explicit
external inputs for profiles that use them.

The same invocation generates the actual 56 candidate POMs without upstream version overlays,
records their dependencies, checks public direct-root POM availability, and traces unresolved
compile/runtime roots through the candidate graph. `maven-closure.json` names affected artifacts
and keeps HTTP failures. This records the source-pinned publication gap; the separate full public
transitive-resolution and signing preflight remains required for Maven publication.

The [S1 evidence](refactor/evidence/source-cli-20261015/README.md) distinguishes the fresh
baseline, runtime control and final candidate qualification. S1 belongs to
`bd-01M50K8E8KMRGY0MYXJRT0SZ8G`, tagged `reality-check-20261015`. Source/CLI qualification
does not close the Maven or corrected-Native release obligations below. Central/signing setup
and dependent Maven publication are deferred from this immediate route.

The current Native platform prerequisite is `bd-01M4YP83C7925M22V0K5AQ88BX`. Native 0.5.12
on macOS arm64 reproducibly crashes when hashing a literal NUL string, including without any
StoryModel type. Both Clang 15 and 20 reproduce it. Constructing the identical character at runtime
preserves the full identity-test coverage and passes, but does not repair that compiler/runtime
defect. [Retained controls](refactor/evidence/release-freeze-20261014/native-diagnostic/receipt.json)
keep this separate from the application repairs. Qualify the underlying behavior before
unrestricted stable Native publication. A reviewed zero-hash runtime patch now has
[separate qualification evidence](refactor/evidence/native-runtime-20261015/README.md).
It skips the redundant write to constant literal storage without changing the hash algorithm.
The patch does not change StoryModel's selected runtime; delivery of a corrected runtime
remains required.

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

## Deferred publication and remaining access

The later 15 October reality-check decision selects GitHub source pins for immediate delivery.
The publication preparation in this section is retained for the future Maven route; account
and signing work is deferred from S1. These prepared binary-release branches must not replace
the source recipe: the newer grakern branch expects graph4s/Gale binaries that remain unpublished.

On 15 October the owner authorized the four infrastructure workstreams: dependency publication,
Native repair, namespace/signing setup and site deployment. The new
[dependency candidates](../tools/release/publication-candidates.json) have exact-commit independent
review, 1,006 passing source tests, nine staged JVM artifacts and a fresh binary consumer with
six passing assertions. Grakern now builds its JVM dependency chain from fixed graph4s/Gale
binaries. [Evidence](refactor/evidence/publication-20261015/README.md) records the pushed release
branches and three missing-key refusal controls. Upstream mains and tags have not moved.

Central access remains unavailable: verify `io.github.canardlapin` for the dependency chain and
`io.github.bbuchsbaum` for StoryModel, and supply the credential location/setup method and
signing key through the secret-handling route. No token or private key belongs in chat or git.
The retained artifacts are unsigned local rehearsals. No Central publication has occurred.
[The publication sequence](../tools/release/PUBLICATION.md) starts with graph4s/Gale, then grakern,
and ends with fresh public resolution and StoryModel POM preflight. Final stable-release
acceptance still includes the selected corrected Native runtime and the artifact matrix.

The documentation site is deployed at <https://bbuchsbaum.github.io/storymodel4s/> from reviewed
commit `5958d3d1627d69ef7f0827a49e60604b7ee34654`.
[Run 38749988902](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38749988902)
passed site verification, artifact build and deployment; live page and embedded-artifact checks
returned nonempty HTTP 200 responses. Subsequent successful main documentation workflows deploy
the verified site. Site deployment and Maven publication retain separate receipts.
