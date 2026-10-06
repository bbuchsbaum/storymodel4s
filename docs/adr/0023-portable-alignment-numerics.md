# ADR 0023: portable alignment arithmetic

Status: accepted owner direction; implementation qualified on the recorded target/toolchain tuples.

Owner direction, 2026-10-06: resolve numerical determinism before updating GitHub main.
Tracking: `bd-01M1D215EY4T5BR0VRJ694AMBQ`.

Identical admitted input bits and inference configuration must produce identical HSMM result
bits and mapping-record identities on qualified JVM, Scala.js and Native targets. This contract
does not promise that external embedding providers produce identical input vectors, or claim
scientific calibration from byte agreement.

The historical first difference is a runtime `exp` call on identical inputs. Current result bits
also feed `MappingBindingRender.result`, `DerivationBinding.checkResult` and published mapping
identities; the old observation that production identity did not consume them is superseded.
Platform-labelled goldens preserved the old difference honestly but did not make inference
portable. Hosted Linux JVM now produces some of the previously labelled Native values.

Use one package-private pure Scala implementation of Netlib fdlibm `exp` and `log` in `align`,
with exact coefficient bits and preserved operation order, compiled from identical source on all
targets. No new module or dependency is required. Preserve upstream notices and exact checksummed
reference bytes. NaN payloads and floating-point exception flags are outside the value contract;
finite bits, signed zero, infinity and value-class refusals are within it. This is the specified
fdlibm approximation, not a claim of universal correct rounding.

Reject selecting a golden by operating system, accepting any historical hash, tolerance in a
checksum test, codec rounding, or a JVM-only `StrictMath` substitution. The latter is a useful
independent witness but does not establish Scala.js or Native semantics.

First qualify the unwired kernel against frozen outputs of the original C reference and separate
high-precision accuracy checks. Include historical divergent inputs, branch neighbors, subnormal
and overflow edges and non-finite value classes. The next slice must cover the complete HSMM
transcendental path, including transition logits before normalization; downstream signature and
population claims require their own complete call and summation-order audit.

Preserve historical pins and their raw witnesses. A numerical producer revision must be explicit
before replacing current pins with one shared artifact; unchanged configuration knobs alone do
not identify the new engine. Do not manufacture executed-stage provenance for historical records.
Measure full results, Viterbi/argmax decisions and identity propagation before integration, and
obtain independent exact-revision review. No production inference or golden changes are made by
the first kernel slice.

The wired numerical producer is `alignment-fdlibm/exp-log-v1`, bound with exact source revision in
qualification receipts. HSMM transition sigmoids, normalization, posterior/flow exponentiation and
likelihood use the owned kernel; entropy, visitation and signature/population readouts use it too.
Signature totals use canonical state/pair order and leaf-importance reductions use key order.
Unrelated calibration fitting, density, Sinkhorn, feature preprocessing and external providers are
outside this producer contract; its input values must already be admitted and fixed.

`HsmmConfigFingerprint` remains the v1 identity of configuration knobs, and is not relabelled an
engine identity. The numerical producer revision does not make a supplied result an execution
receipt: `HsmmResult.validated` still proves structural/admissibility laws, not execution, and
historical mappings retain unknown provenance. `hsmm/v4` remains the wire schema because its
fields and admission contract do not change. Historical v4 values remain contextual input values;
decoding does not silently re-execute them under the new producer. New execution receipts must bind
the numerical revision as well as configuration and exact source, rather than claiming an old
configuration fingerprint identifies an execution.

No current golden may be recut simply to make the test green. Independently qualify one live
producer output on all targets first; keep all historical resources intact. Where the portable
producer exactly preserves a historical pin, retain that equality as a measured regression fact.

Qualification is bound to producer `433aa1056f6aa5e88b63b4b665079e9959a266ae` in the
[October checkpoint](../refactor/evidence/workstation-checkpoint-20261006/README.md): full hosted
JVM17/21, JS17 and Native17 matrix; macOS ARM64 Java21 full coverage plus Native ReleaseFast
courts; complete common-result/readout/record captures and unchanged historical resource bytes.
StoryAtlas exactd67 consumes the same declared source pin and passes hosted tests and workstation
public exports/readback. Tests cover the frozen631-vector original-C oracle and917 separate
high-precision checks, including historical witnesses and arithmetic boundary cases. Neither
these finite cases nor engineering byte agreement establish scientific calibration or universal
correct rounding. The compiled runtime-exp mutation remains a named discriminator.
