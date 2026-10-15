# Dependency publication handoff

The owner authorized the four publication workstreams on 15 October 2026.
`publication-candidates.json` fixes the reviewed release branches and nine JVM
coordinates. The older `dependency-candidates.json` remains the input to the
historical source-overlay rehearsal; do not substitute the new manifest into
`stage-dependencies.py`.

## Account setup needed

The dependency releases use `io.github.canardlapin`; StoryModel uses
`io.github.bbuchsbaum`. Verify each namespace in its owning Central Portal account.
GitHub authentication and repository administration do not prove Central ownership.
As of this checkpoint, no publisher token or private signing key was available to
the agent, and the four repositories had no Actions secrets.

Provide the credential location or setup method to the agent, never secret values
in chat. The Central user token supplies `SONATYPE_USERNAME` and
`SONATYPE_PASSWORD` to the publishing process. Keep account credentials isolated.
Use the selected GPG signing key through the local keyring; make its public key
available as required by Central. These candidates use `sbt-gpg`, which signs
ordinary `publish` tasks. Missing signing keys fail publication; three retained
empty-keyring controls verify that refusal.

References: [Central namespaces](https://central.sonatype.org/register/namespace/),
[sbt Central publishing](https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html),
[sbt-gpg](https://github.com/jodersky/sbt-gpg).

## Release sequence

1. Check out each exact manifest commit in a new standalone clone. Use the
   `canardlapin` Git/API route. The prepared branches are already pushed; repository
   mains and tags have not moved. Use JDK 21 for the dependency checks.
2. Publish graph4s and Gale first. In each clean candidate, run the manifest's
   test commands, then `sbt stageDependencyRelease`. That alias stages only the
   listed JVM modules into sbt's local staging directory. Inspect `show localStaging`
   for its actual location. Do not reuse the unsigned rehearsal directory.
3. Validate the exact coordinate set, POM metadata, dependency closure, binary,
   sources and Javadoc JARs, detached signatures and checksums. Verify signatures
   against the intended public key, and retain bundle and source hashes.
4. Run `sbt sonaUpload` to upload the signed bundle for Central validation. Retain
   the deployment ID and result, then publish the validated bundle in the Portal.
   `sonaRelease` combines upload and release; do not invoke it before the local
   bundle checks. The existing owner authorization covers this bounded release.
5. Confirm graph4s and Gale resolve from Maven Central with empty caches. Then
   repeat the checks and staging for grakern, whose JVM build now uses those fixed
   binary coordinates. Its JS and Native source pins are outside this JVM release.
6. Resolve all nine public artifacts with fresh caches and run
   `UpstreamBinaryConsumer.scala`. Rerun StoryModel's actual POM preflight: all
   four previously missing grakern roots must resolve publicly. Bind every result
   to the released bytes. A local repository result cannot close this step.

The 15 October rehearsal passed 1,006 source tests and six external binary
assertions against nine unsigned local artifacts. It establishes source and
binary compatibility for these candidates, not signatures or Central acceptance.
StoryModel's stable release gate also retains the corrected Native runtime
delivery prerequisite. The documentation site is deployed independently.
