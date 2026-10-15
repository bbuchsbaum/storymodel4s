# Release prerequisite evidence

This record binds the constructor repairs, member-level stability decisions and local
publication preparation requested on 14 October. It is not a release certificate.

## Constructor slice

The candidate is `8ea96d6a91e88144eff132c185cb3663047d20b9`, based on
`b78177f5190bcb77f9fce3156b1a9137df37feba`. It closes ClaimLedger product construction,
checks historical candidate abstention, rejects nonpositive embedding truncation limits,
and refuses nonfinite HSMM refinement weights. The final commit migrates the benchmark's
deliberately infinite configuration to assert factory refusal and retains a finite-extreme
trace control.

[Commit boundaries](constructor-commits.json), [reference scope](constructor-reference-scope.txt),
[mutation commands](constructor-mutation-commands.json), and the
[initial independent review](constructor-review-8c04ba21.json) retain the precise evidence.
The initial review is a code verdict on `8c04ba21`, with gates explicitly pending.

[Producer gate receipt](constructor-combined-gate-receipt.json) records two invocations.
The first ran the full fatal-warning gate on `8c04ba21`: 52 tasks passed before the old
benchmark assertion failed. The `8ea96d6a` delta changes only that benchmark test. Its rerun
passed the benchmark and the three remaining JVM modules, formatting and distribution build.
Across unchanged inputs and rerun tasks: **10,964 passed, zero failed/errors, five optional
skips, 56 task totals**. This is combined evidence, not a claim that the failed invocation
passed. The [clean pinned StoryAtlas gate](constructor-atlas-receipt.json) passed all 518 tests.
[Final independent review](constructor-review-8ea96d6a.json) approved the exact candidate,
including unchanged-input reuse. Local and GitHub main were advanced to that candidate.

The old-code controls and compiled guard mutations are retained in the compressed logs listed
by [the log index](log-index.json). Hashes cover compressed and uncompressed bytes. Earlier
inconclusive compilation and stale-probe runs remain in the session's ignored evidence directory;
they are not counted as passing proof.

## Promotion findings and decisions

The independent [compiled probes](promoted-boundary-probes/receipt.json) and
[additional probes](promoted-boundary-probes/additional-receipt.json) reproduce eight defects
against the checksum-verified `eb48a562` installed distribution. They cover media construction,
nonfinite standalone layer-use, receipt product construction and consistency, custom semantic
identity collision, frame-clock and geometry arithmetic, and alignment-row aggregate overflow.
Their production inputs are unchanged through the constructor slice. The probes establish
publicly executable counterexamples; they do not establish occurrence in the qualified study.

[Four bounded repair tickets](promotion-repair-motes.json) retain these obligations, including
the related CaptionRecipe construction opening. The repaired runtime is `d7bc9c06dfe8c441e020c826b895616f914b157f`.
Its [combined producer gate](promotion-combined-gate-receipt.json) records **11,067 passed,
zero failed/errors, five optional skips, 56 task totals**. The initial full gate completed the
first 30 tasks before a later Native string-literal defect interrupted execution. Those 6,665
passes have unchanged inputs; all embedding-core and subsequent tasks were rerun after the
equal-content fixture repair. The initial failed invocation remains in the receipt and is not
described as passing. Formatting completed last in the successful tail.

The [family review](promotion-family-review.md) covers the 29 supplied source paths and retains
declaration/admission and inspection/execution distinctions. [Compiled mutations](promotion-mutations/receipt.json)
kill each repaired guard or construction opening; the [identity rerun](promotion-mutations/identity-d7bc/receipt.json)
rechecks the length-delimited encoding after the Native fixture adjustment. The API freeze
remains pending until the repaired compiled distribution, exact member manifest, construction
judgments, pinned consumer evidence and independent review agree.

Member-aware audit controls distinguish supported accessors from experimental factories,
require classification of every exposed member and traverse enum alternatives. The retained
controls include eight closure tests and three compiled compatibility comparisons: unchanged,
an experimental-factory rename, and a supported-accessor removal. These do not substitute
for the final library manifest or for binary compatibility checks.

The [compiled boundary receipt](stable-api/receipt.json) and
[supported-member manifest](stable-api/supported-api.json.gz) bind source `63a952f9`.
The complete [semantic inventory](stable-api/semantic-inventory.json.gz) has 1,544 reachable
nominals and 12,833 exposed members; the selected boundary supports 1,184 nominals and 9,455
members. [Provenance](stable-api/inventory-provenance.json) binds the verified
[distribution manifest](stable-api/distribution-manifest.json). The exact data equal the
previous repaired inventory except for the source SHA.

The [decision recipe](decide-boundary.py) excludes five imported-value construction helpers
explicitly. This closes the incidental narrative-graph path while preserving the selected
readers and public inspection. Four `acquire` output-ID aliases remain required by public
workspace accessors; acquisition strategies remain experimental. The complete graph still
contains excluded construction edges. To check the saved boundary:

```sh
gzip -dc docs/refactor/evidence/release-freeze-20261014/stable-api/semantic-inventory.json.gz > /tmp/storymodel4s-inventory.json
gzip -dc docs/refactor/evidence/release-freeze-20261014/stable-api/supported-api.json.gz > /tmp/storymodel4s-supported.json
python3 tools/stability-audit/validate-supported-closure.py /tmp/storymodel4s-inventory.json /tmp/storymodel4s-supported.json
```

## Native qualification

Two findings remain distinct. Main `8ea96d6a` failed Linux Native CI twice in `MappingRunSuite`,
with a process crash and, on retry, glibc heap-corruption diagnostics. The original failed
executables were not uploaded. JVM and JavaScript cells passed. Main integration is held while
a source-pinned Ubuntu 22.04/LLVM 18 comparison retains its executable and generated LLVM before
testing. That [Linux comparison](native-diagnostic/llvm18-linux/receipt.json) passed 1,030
alignment tests, including all six `MappingRunSuite` tests, with every retained artifact hash
unchanged. Actual Native configuration selected Clang 18.1.8. This qualifies that configuration
without establishing the cause of the earlier failures. The generated CI candidate `63a952f9`
adopts the same toolchain and retains alignment artifacts before testing; its full hosted
matrix is the next gate. [Workflow review](native-diagnostic/workflow-review.json) and
[shell controls](native-diagnostic/workflow-shell-controls.json) bind that diagnostic mechanism;
the runtime result is bound separately in the Linux comparison receipt.

Separately, macOS Native 0.5.12 crashes hashing a literal NUL string with both Clang 15 and 20,
including outside all StoryModel types. [Diagnostic controls](native-diagnostic/receipt.json)
retain the plain-string witness. Constructing the same character at runtime preserves all 121
identity-pair assertions and passes. This fixture adjustment does not repair the underlying
compiler/runtime defect; `bd-01M4YP83C7925M22V0K5AQ88BX` remains a prerequisite to unrestricted
stable Native publication.

## Publication preparation

The dependency-candidate and preflight tools prepare fixed coordinates, validate actual POMs,
and separate clean-cache public resolution from local staging. Upstream release versions are
proposals; no Maven artifact, tag or documentation deployment is authorized by this record.
Namespace/signing qualification, publicly available upstream dependencies and the final
release gate remain distinct acceptance.
