# CLI vertical-slice recovery bundle

This directory is an exact rescue copy of the task-local code and QA evidence
that otherwise existed only in system `/tmp` or the repository's ignored
`tmp/` directory on 2026-08-31.

It is a recovery handoff, not a production source tree and not a claim that the
CLI has landed. The production SurfaceAtlas contract is represented by Mote
candidate `cand-08QSQGY8XMA3FMETGHVR6ZKN1K` at exact Git commit
`9d8b4a34283e7efb07d097fee120926b51e78080`.

Contents:

- `SurfaceArtifactDemo.java`: strict-UTF-8 stdin-to-SurfaceAtlas artifact demo.
- `Utf8RoundTripOracle.java`: independent fixed-seed UTF-8 admission oracle.
- `SurfaceAtlasSourceIdentityOracleSuite.scala`: source identity/checksum court
  created in a detached standalone clone and not present in the candidate.
- `surface-atlas-demo-input.txt`: synthetic UTF-8 input; no admitted story or
  participant text.
- `surface-atlas-demo.json`: real canonical artifact output.
- `surface-atlas-qa.html`: interactive local visual-QA prototype.
- `storymodel4s-surface-identity-oracle-9d8b4a3.log`: oracle execution receipt.

The derived `.class` files were not retained because they can be rebuilt from
the Java sources. This directory is intentionally not ignored, so `git status`
will keep the recovery bundle visible until the chief decides its final custody.

