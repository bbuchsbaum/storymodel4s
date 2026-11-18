# JVM Sinkhorn transport oracle

This is executable synthetic JVM evidence from `storymodel4s` commit
`7bcc5531877f8c7916f5957edf505cccc906c689`, not a reimplementation. The
test-scope runner reuses the checked `AnnaFixture` and calls both
`UnbalancedSinkhorn.solve` and `BaselineAligner.align`.

The stored JSON is the runner's complete one-line output, losslessly pretty
printed. Forbidden input costs are represented as the string `"+Infinity"`
because JSON has no non-finite numeric literal; this preserves the Scala
`Double.PositiveInfinity` input exactly. It records every input cost, marginal
and config, every output plan/marginal, iteration count and convergence flag.

## Replay

From a checkout whose `HEAD` is the pinned SHA, create a standalone shared
clone (sbt cannot build in a linked worktree), copy the runner into test scope,
then run it twice:

```sh
probe_root=$(pwd)
probe_clone=$(mktemp -d /private/tmp/storymodel4s-sinkhorn.XXXXXX)
git clone --shared --no-checkout "$probe_root" "$probe_clone"
git -C "$probe_clone" checkout --detach 7bcc5531877f8c7916f5957edf505cccc906c689
cp "$probe_root/port/evidence/transport/SinkhornJvmEvidence.scala" \
  "$probe_clone/align/src/test/scala/storymodel4s/align/"
cd "$probe_clone"
sbt 'alignJVM/Test/runMain storymodel4s.align.SinkhornJvmEvidence' > run-1.log 2>&1
run_1_exit=$?
sbt 'alignJVM/Test/runMain storymodel4s.align.SinkhornJvmEvidence' > run-2.log 2>&1
run_2_exit=$?
printf 'run-1=%s run-2=%s\n' "$run_1_exit" "$run_2_exit"
rg '^\{"schema"' run-1.log > emission-1.json
rg '^\{"schema"' run-2.log > emission-2.json
diff -u emission-1.json emission-2.json
jq . emission-1.json > emission-pretty.json
jq -S -c . emission-1.json > canonical-raw.json
jq -S -c . emission-pretty.json > canonical-pretty.json
cmp canonical-raw.json canonical-pretty.json
sbt 'alignJVM/testOnly storymodel4s.align.SinkhornSuite'
```

Captured command exit statuses: runner one `0`, runner two `0`, byte-identical
emission comparison `0`, structural raw/prettified comparison `0`, and
`SinkhornSuite` `0` (17 total, 17 passed, 0 failed, 0 errors).

## Observed scope

The baseline demonstrates the canonical union of distributed per-unit nominations:
`u0 -> e4`, `u1 -> e5`, `u2 -> e5`, `u3 -> e4`, hence columns
`[e4, e5]`. Its only `u0/e4` comparison is explicitly ineligible and therefore
has `+Infinity` input cost and exactly zero plan mass. All other absent provider
values are imputed as the declared `0.5` missing distance.

The zero-prior/zero-row-penalty case shows actual pinned behavior: a zero target
row is unconstrained under `rhoRows = 0` and carries nonzero mass. The
positive-column-only-zero-row case returns an all-zero plan after 200 iterations
with `converged = false`; it is evidence of this pinned implementation's
NaN-to-zero masking behavior, not proof of a typed refusal. These are synthetic
local engineering observations only, not a corpus result, calibration, provider
execution, or full Scala/Python parity claim.

