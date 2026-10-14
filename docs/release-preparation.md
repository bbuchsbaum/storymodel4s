# Release preparation

The installed CLI and executable guide are qualified local delivery artifacts. The current
publication gate is `bd-01M2TAMJ95K9D7H248A8387YW4`; the infrastructure ticket is
`bd-01M2TAKTB61XNXV4Y6A9J3CB0C`. This checklist records preparation for that gate. No stable
artifact, tag or deployed documentation site follows from a local build.

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

The rehearsal's baseline policy permits an empty baseline for the first `1.0.0`, accepts `1.0.0`
for later versions, and refuses an empty baseline for `1.0.1` and `1.1.0`. This tests the policy
helper; it is not yet an activated published-artifact comparison in CI. After the first stable
artifact exists, bind `tlMimaPreviousVersions` to its exact coordinates, integrate the nonempty
baseline guard, and run a named stable-symbol break through the actual release workflow.
Experimental exclusions must be explicit and reviewed. This local Scala 3 control does not
establish JVM bytecode, source, JavaScript/Native, or wire compatibility for the whole library.

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
