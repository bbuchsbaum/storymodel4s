# Pre-freeze stability audit evidence

This directory is generated from source SHA `2c4b74f9ef069e1bc1f6678f8dababe2421ca929` by
`tools/stability-audit/run.sh`. The semantic inventory is intentionally root-directed and keeps
Scala signature closure separate from the command/wire inventory. It is evidence for promotion
decisions, not a stability freeze or release certification.

The verified run has 27 explicit roots, 674 reached symbols and 5,792 typed edges. It reaches
the required `MappingRun.Execution` result arms, `LocalEvidence` to `CandidateSet`,
`StructuredReconstruction.Profile` to `HsmmConfig`, and the batch outcome/run route to
`OnnxMappingRegistration.Registered`; it does not reach `GraphHsmm` or
`MappingPipeline.Organization`. It retains both `MappingPipeline.analyze` overloads separately.
External traversal stops at Cats and Circe symbols (listed in `semantic-inventory.json`), not
their implementation signatures.

`tools/stability-audit/verify.sh` reruns the TASTy audit and compiles two controls against the
same installed distribution, with `-Xmx2g -XX:ActiveProcessorCount=2`: public
`MappingPipeline.Config.copy` compiles, while direct construction of `LocalReference.Result`
does not. Its independently compiled TASTy fixtures prove parameter-only dependencies,
lower/upper type bounds, overload-only dependencies, nominal opaque aliases, inherited
`OpaqueId.T`, case-class construction/default/copy metadata, a given, and an extension method.
The walker reports direct signature edges and their transitive type symbols, but does not prove
JVM ABI compatibility, bytecode bridge/default-getter ABI, or wire compatibility. Those remain
separate review obligations before a freeze.
