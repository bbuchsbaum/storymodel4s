# Immutable result identity cache

The actual annotation-cohort development run was stopped before producing a
completed corpus workspace. A thread sample located repeated whole-result
rendering in `MappingLink.fromResult` through `DerivationBinding.checkResult`.
The baseline log and explicit stop receipt are retained separately; this is not
a completed baseline or a measured speedup claim.

Independent reviewer reconstruction_review approved per-instance memoization:
the rendered `HsmmResult` graph contains immutable collections, closed values and
captured support coordinates. It retains no provider, callback, mutable array or
live `SourceView`. The implementation keeps the full renderer unchanged and uses
a private lazy digest only in binding creation and comparison. It adds no field
to equality, serialization or identity inputs.

The existing one-bit posterior witness now compares both cached identities with
independent full rendering, checks repeated access, and retains rejection by the
old binding. Development verification passed all 24 numerical/mapping-measure
tests. Exact-revision mutation and cross-platform gates remain separate.
