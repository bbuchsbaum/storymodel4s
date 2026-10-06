# ADR 0023: portable alignment arithmetic

Status: implementation proposal under numerical qualification; not yet integrated.

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
