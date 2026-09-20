# D1A S4c — checked alignment source capabilities

All required execution gates pass. Source/test candidate: `369965b2`. This slice seals
`AlignmentSource`, exposes exact `TypedSupport` and admitted `PrimaryProjection`, and
adds `TextAlignmentSource` for exact character spans plus its own checked text witness.
Only validated/adjudicated models enter the public factories. `StorySourceView` now
consumes that text capability directly; there is no independent model/text argument to
pair with an unrelated source. Missing node IDs remain `None`.

The [source comparison](unchanged-scoring-source.json) binds unchanged relation and
scoring tokens against S4b, separately from executable numerical preservation. The runtime wrapper comparison
checks consistency across general/text and validated/adjudicated entry points; it is
not an independent historical numerical oracle. Native anchors remain in original
support, while primary projection retains the entire direct-primary interval union,
including gaps. This introduces no clock conversion or point support.

The [cold reviews](code-review.json) found no material source/test defect at the stated
checkpoints. The [all-platform production compile](production-compile-1.json) passed at
`abb2391d`; the [focused JVM court](focused-1.json) passed 26 tests at `862cea93`.
These are intermediate receipts, not the final release gate.

The [24-case inventory](guard-witness-inventory.json) binds exact source and mutant
hashes, named failing courts, and passing controls. Fourteen compile-door mutants
require both `Compile/clean` and `Test/clean`, followed by actual production and probe
compilation. Initial Test-only-clean attempts are retained as historical evidence and
are excluded from final qualification. The first text-constructor mutation was invalid:
a widened subclass cannot expose a still-private superclass in its Scala signature.
The compilable replacement widens both, with a separate base-only mutation retained.

The [terminal mutation summary](mutation-terminal-summary.json) records 24 compiled
kills, 24 named passing controls, 14 clean production/probe recompiles, and 26 passing
restored controls on a clean `369965b2` clone. The [evidence archive](mutation-evidence.tar.gz)
retains all final and historical command receipts, logs, XML, and source hashes; its
[member manifest](mutation-evidence-manifest.json) binds each file.

The [runner](qualify.py) restores each exact original source and checks cleanliness
before the next mutation. The [HSMM comparison runner](hsmm-parity.py) compares actual
captured bytes with each corresponding immutable
[pre-migration baseline](../wog-hsmm-baseline-20260919/README.md), preserving runtime
labels and the five existing Native/JVM mass differences. The capture harness is
byte-identical to the baseline harness. Native bytes are host-specific evidence,
not a universal cross-OS guarantee or a new tolerance policy.

A correction to earlier plan wording is explicit: the WOG checksum was not wholly
unused. The existing JVM `WarOfTheGhostsCodecGoldenResourceSuite` compares the inferred
artifact with the committed resource and fixed checksum; the portable two-test suite
checks canonical/contextual round-trips. S4c retains both and adds the separate
three-backend preservation court. A real text-length perturbation must fail the JVM
inferred-artifact court, separately from construction refusal.

To reproduce, create a clean standalone clone at the exact source/test candidate and
an exact grakern checkout at `0329c43c88a0b71e9aa4456723bb16bac2fa3841`. Set
`D1A_S4C_REPO`, `D1A_S4C_GRAKERN`, and a fresh `D1A_S4C_OUTPUT`; run
`python3 qualify.py mutations` and `python3 hsmm-parity.py` at `369965b2`.
After confirming the evidence-only difference in provider-binding.json, advance the clone
to `7a35bff7` and run `python3 qualify.py release`. The consumer runner asserts exact
consumer `d95b9ea` and provider `7a35bff7` plus both immutable sibling pins. Existing output logs are never overwritten.

The [terminal HSMM comparison](hsmm-parity.json) passed on all three backends at
`369965b2`: JVM/JS `ce61e761…2cf1a`, Native `ccb0b98f…af012`, each 26,284 bytes,
exactly equal to its immutable baseline. All ten named tests passed (four JVM, three
each JS/Native). The [capture archive](hsmm-parity-evidence.tar.gz) and its
[member manifest](hsmm-parity-evidence-manifest.json) preserve actual bytes, raw logs,
fresh XML and command/runtime receipts. Each byte-comparator mutation was rejected.

The [clean full provider gate](full-gate.json) at `7a35bff7` passes all 56 tasks:
7,001 total / 6,996 passed / five existing skips / zero failures or errors.
[Formatting](format-last.json) and [all 13 documentation examples](docs-examples.json)
pass separately. The [provider archive](provider-evidence.tar.gz) and its
[manifest](provider-evidence-manifest.json) retain command logs and all 609 fresh JUnit
reports. [Source binding](provider-binding.json) shows only evidence changes after the
mutation/parity candidate and verifies frozen S0. The unchanged NFRD test import warning
is also present in S4b; no warning-clean claim is made.

The [scoring perturbation comparison](score-perturbation-differences.json) observes 101
changed leaves, including posterior/flow masses and likelihood as well as fingerprint.

The [exact pinned consumer gate](consumer-gate.json) passes all 245 tests across eight
tasks (both edition tasks have no test sources), formatting and the production app link.
The [consumer binding](consumer-binding.json) and [archive](consumer-evidence.tar.gz)
with its [manifest](consumer-evidence-manifest.json) retain all 24 fresh JUnit reports,
command receipts and the production bundle hash. All four clones remain clean.
Only the immutable provider pin changed in the consumer; no browser smoke was rerun.

The [independent audit](root-independent-audit.json) reconciles archive contents, provider
and consumer counts, frozen resources and exact revisions with no findings. The consumer
is [landed locally](consumer-local-landing.json), as is provider `146df652`.
The [landing record](local-landing.json) preserves the tracker state at the time of that
landing. S4c was closed on 2026-09-20 after evidence reconciliation; its historical
`doing` status and pending-closure field are superseded by this statement and the D1A-types
parent reconciliation note. D1B still owns point-capable support, all 17 participant parity,
and the typed recall path. This slice makes no film compiler, caption licensing, empirical
accuracy, remote publication, or executed CI claim.
