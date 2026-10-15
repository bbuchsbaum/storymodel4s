# Publication preflight

`preflight.py` qualifies candidate Maven POMs without publishing, tagging, credentials, or
repository writes. It requires an exact candidate matrix rather than accepting a matching count.

```json
{
  "schema": "storymodel4s-release-candidate-matrix/v1",
  "coordinates": [
    {
      "groupId": "io.github.bbuchsbaum",
      "artifactId": "storymodel4s-core_3",
      "version": "1.0.0",
      "packaging": "jar"
    }
  ]
}
```

Run it with POM paths or directories and a supplied Coursier executable:

```sh
python3 tools/release/preflight.py \
  --expected candidate-matrix.json \
  --coursier /absolute/path/to/cs \
  --output artifact-preflight.json \
  path/to/candidate-poms
```

It requires full project metadata, fixed non-SNAPSHOT versions, and candidate-internal dependency
closure. For each external direct dependency, it runs `cs fetch --no-default` against Maven
Central in a fresh `COURSIER_CACHE`; Coursier then resolves that dependency's full external
transitive closure and writes a JSON report. Every reported coordinate is fixed, every reported
JAR must exist under that isolated cache, and every public URL must originate at Maven Central.
The receipt keeps every command, exit code, log hash, JSON-report hash, candidate-POM hash,
Coursier executable hash, and fetched JAR hash. It uses isolated Coursier and home/config paths
and an empty `COURSIER_CREDENTIALS` setting; this is an isolation mechanism, not a claim about
the user's global credential stores.

For `1.0.1` and later `1.x` candidates, supply exact `1.0.0` baseline POMs with one or more
`--baseline` inputs. Empty baseline input is admitted only for the first `1.0.0`. An optional
`--staging-repository URL` adds a second fresh-cache rehearsal that fetches every candidate
coordinate from the named staging repository (with Maven Central available only for its external
dependencies) after the public Maven Central resolution; it is evidence distinct from
public-dependency qualification.

## Pinned upstream staging

`dependency-candidates.json` proposes fixed release-candidate coordinates for the exact graph4s,
gale and grakern source revisions already consumed by this build. Prepare independent clones:

```sh
python3 tools/release/stage-dependencies.py --output /absolute/new/directory
```

Add `--run` to execute the recorded JVM tests and publish the selected artifacts into that
directory's file Maven repository. `--source graph4s=/local/git/checkout` (and the corresponding
gale/grakern options) can supply the pinned Git objects without changing those source checkouts.
Every target receives an explicit local publication destination. The command refuses an existing
output directory and retains source revisions, build overlays, commands, log hashes and artifact
hashes. It checks the actual POM matrix, metadata, fixed dependency versions and internal closure.

The release-candidate versions remain proposals. Local staging proves neither public resolution
nor namespace ownership/signing. Complete a fresh binary-consumer rehearsal, qualify external
dependencies against Maven Central, and obtain publication authorization before publishing them.

```sh
python3 tools/release/rehearse-upstream-binaries.py \
  --staging /absolute/path/to/completed-upstream-staging \
  --coursier /absolute/path/to/cs \
  --output /absolute/new/binary-consumer-directory
```

This serves the verified staged repository on an owned loopback server, resolves all nine
coordinates in fresh caches, and compiles and runs an external Scala consumer. The consumer
checks six normalized-kernel and query results across graph4s, grakern and Gale, with exact
zeros and a `1e-12` tolerance for ones. Fetched root hashes must match the staging receipt. External
dependencies and the compiler resolve from Maven Central. The server closes before the receipt
is written. This qualifies the local binary chain, not public availability of the proposed roots.

## Candidate metadata and baseline controls

After upstream staging succeeds, generate the complete storymodel4s POM matrix from a clean
standalone checkout:

```sh
python3 tools/release/prepare-candidate.py \
  --checkout /absolute/path/to/clean-storymodel4s \
  --upstream-staging /absolute/path/to/completed-upstream-staging \
  --output /absolute/new/metadata-directory
```

The command verifies upstream revisions, version-overlay bytes, the allowed grakern source
routing patch, and staged as well as unstaged source changes before invoking sbt. It then
generates and validates all 56 actual POMs against `candidate-matrix.json`. This prepares
metadata; run the public-dependency preflight separately.

`python3 tools/release/test_prepare_candidate.py` checks that unchanged prepared sources reach
an sbt sentinel and both staged and unstaged unexpected edits refuse before the build starts.
`rehearse-baseline.py --checkout CLEAN_CHECKOUT --output NEW_DIRECTORY` exercises the actual
sbt release-baseline guard and MiMa tasks with a synthetic local artifact. Its baseline is a
fixture, not a published storymodel4s release. Rebuild ordinary outputs after running it.
