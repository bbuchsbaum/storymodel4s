# Publication infrastructure, 15 October 2026

The owner authorized dependency publication, the Native runtime repair,
namespace/signing setup and documentation deployment. The infrastructure ticket
is `bd-01M2TAKTB61XNXV4Y6A9J3CB0C`; final release acceptance remains on
`bd-01M2TAMJ95K9D7H248A8387YW4`.

## Dependency candidates

The three independently reviewed commits are retained on each repository's
`codex/publication-20261015` branch:

| Repository | Exact commit | Passed | Failed/errors/skipped |
| --- | --- | ---: | ---: |
| graph4s | `10e29e2a496b9ac00e299b560faa1462d122ccaa` | 26 | 0 |
| Gale | `9e7d1e7d0325d8e8b7989b4acd8c6c7328c1dbd8` | 637 | 0 |
| grakern | `539a9e16d5ef36ede721fb43fe4a89e95dacd52d` | 343 | 0 |

These candidates add fixed RC versions, metadata, sbt local staging and signing.
Grakern's JVM dependencies now name the fixed graph4s/Gale binaries. Gale gains
the same signing plugin used by the other builds. Application sources are unchanged.
The release scope is nine JVM artifacts, rather than every upstream module/platform.

[Qualification](qualification.json) records the final serial commands on clean
candidate trees: 1,006 passes across 11 tasks. [Staging](unsigned-staging.json)
binds artifact hashes and nine valid POMs with complete internal dependency closure.
[Binary consumption](binary-consumer.json) resolved all nine roots in fresh caches,
checked their JAR hashes and executed six external assertions. Its owned loopback
server was closed. [Three missing-key controls](signing-refusal.json) independently
refused publication and staged no artifacts.

The successful staging invocation deliberately overrode signing refusal only for
its explicit local file destination. Its artifacts are unsigned. No Central token
or signing key was available, and Central namespace ownership is unverified.
No upstream main or tag moved; no Maven artifact was uploaded or published.
The four grakern roots therefore remain a public-resolution prerequisite.
[Publication instructions](../../../../tools/release/PUBLICATION.md) identify the
account setup and exact remaining sequence.

Retained failures include Gale's successful tests followed by an unavailable
formatting task, the wrong grakern file-resolver form and one sandbox cache denial.
The final run uses a Maven file URL and passes. [Log hashes](logs.json) identify
compressed raw evidence, including those failures.

## Documentation deployment

Reviewed commit `5958d3d1627d69ef7f0827a49e60604b7ee34654` landed on local and remote
main and deployed to <https://bbuchsbaum.github.io/storymodel4s/>.
[Run 38749988902](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38749988902)
passed verification, artifact build and deployment at that exact SHA.
[Hosted receipt](pages-hosted.json) and [live checks](pages-live.json) retain the
three nonempty HTTP 200 responses for the homepage, evidence page and iframe artifact.

The site uses the repository base path. Independent review caught two missing
slashes in a receipt link and iframe. Both were repaired; the navigation court
checks 33 local URLs and kills each malformed-path mutation separately. Full local
verification passed 14 guide examples, 21 pages, six layout checks and artifact
provenance. A prior disk-full invocation remains adverse evidence; the subsequent
complete run and affected-check rerun passed. Browser automation was closed and
its final audit found no owned browser left running.

Deployment completes the site workstream. It does not establish Maven publication
or delivery of a corrected Scala Native runtime.

Main's [full CI run 38749988858](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38749988858)
also passed at `5958d3d1`: 15,474 passed, zero failures/errors and 44 optional skips
across 80 task totals. [Parsed totals](main-ci-totals.json) bind the complete log
archive. The subsequent publication tools/evidence slice changes no StoryModel
production source, module test or build input; those producer results remain
applicable to the unchanged library. The separate Native repair workflow qualifies
the retained upstream patch, rather than silently replacing the library runtime.
