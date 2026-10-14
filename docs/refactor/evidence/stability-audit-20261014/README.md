# Pre-freeze stability audit evidence

This directory is generated from source SHA `2c4b74f9ef069e1bc1f6678f8dababe2421ca929` by
`tools/stability-audit/run.sh`. The semantic inventory is intentionally root-directed and keeps
Scala signature closure separate from the command/wire inventory. It is evidence for promotion
decisions, not a stability freeze or release certification.

The verified run has 27 explicit roots, 364 reached symbols and 1,865 typed edges. It reaches
the required `MappingRun.Execution` result arms, `LocalEvidence` to `CandidateSet`,
`StructuredReconstruction.Profile` to `HsmmConfig`, and the batch outcome/run route to
`OnnxMappingRegistration.Registered`; it does not reach `GraphHsmm` or
`MappingPipeline.Organization`. External traversal stops at `cats.data.NonEmptyVector` and
`io.circe.Json`.

`tools/stability-audit/verify.sh` reruns the TASTy audit and compiles two controls against the
same installed distribution: public `MappingPipeline.Config.copy` compiles, while direct
construction of `LocalReference.Result` does not. The walker reports direct signature edges and
their transitive type symbols, but does not prove JVM ABI representation of opaque types,
generated bridges/default getters, extension ownership, every package-scoped synthetic accessor,
or wire compatibility. Those are still review obligations before a freeze.
