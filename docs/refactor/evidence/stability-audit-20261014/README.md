# Pre-freeze stability audit evidence

The original inventory below is historical and incomplete: it omitted public enum/sealed
alternatives. The corrected reviewed population at the same distribution source contains
**2,058 symbols and 21,499 edges, with zero unresolved symbols**; see
[the compressed inventory](semantic-inventory-repaired-2c4b74f9.json.gz),
[provenance](semantic-inventory-repaired-2c4b74f9.provenance.json),
[construction judgment](constructor-judgment.md), and [exact review](review-874f1b08.json).
The supplemental TASTy hashes and admitted runtime JAR set are now part of the audit contract.
Neither this repair nor the zero-unresolved count establishes a final stable API freeze.

## Final distribution and complete coverage (14 October)

The final `eb48a5622bf02d08c175d2956811fd35d4aaeb92` CLI archive has a
[retained manifest](audited-distribution-manifest.json). Its refreshed
[semantic inventory](semantic-inventory-final.json.gz) contains **2,060 symbols and 21,505
edges**; the separate [inspection inventory](inspection-inventory-final.json.gz) contains
**1,851 symbols and 19,093 edges**. Both have zero unresolved symbols. Their
[semantic provenance](semantic-inventory-final.json.provenance.json) and
[inspection provenance](inspection-inventory-final.json.provenance.json) bind the final archive
manifest. The [refresh receipt](archive-audit-refresh.json) retains the earlier manifest-bound
audit and confirms identical inventories after the archive rebuild.

The [complete constructor-family crosswalk](constructor-family-crosswalk.json.gz) preserves
every exact semantic inventory identity, including the distinction between class, companion,
opaque alias and enum singleton. There are 945 ClassInfo entries: 840 class types, 47 traits and
58 module classes. All 887 nominal class/trait entries have a root-path witness and source
candidate; this conservative population includes types that are not publicly constructible.
The [counts](constructor-crosswalk-summary.json) associate 718 nominal entries with scoped
family judgments and leave 169 implementation dependencies explicitly pending. These are
coverage counts, not accepted-constructor counts. The narrative dependency family also retains
its experimental boundary even where a broad graph-family judgment is present.

`constructor-judgment.md` adds explicit claim/provenance, text/atlas, source/sidecar, contextual
codec and executed-temporal dispositions. The ledger probe reproduced a known generated-constructor
opening and measured its lossy JSONL consequence; normal builder/readback controls pass. The
candidate-abstention and token-truncation findings remain separately recorded. Final stable
promotion/narrowing and repair acceptance remain required; this audit does not freeze the API.

Reproduce the coverage assignment from the committed inventory and current judgment text:

```sh
python3 docs/refactor/evidence/stability-audit-20261014/constructor_crosswalk.py \
  /absolute/path/to/output \
  docs/refactor/evidence/stability-audit-20261014/semantic-inventory-final.json.gz
```

The classifier is a conservative evidence index, not a Scala visibility parser. Its source
anchors are candidates; its exact root paths establish reachability. The independently prepared
[earlier draft](constructor-crosswalk-2c-draft.json.gz) and
[validation](constructor-crosswalk-2c-review.json) retain how the missing judgments were found.

## Historical pre-alternative inventory

This directory is generated from source SHA `2c4b74f9ef069e1bc1f6678f8dababe2421ca929` by
`tools/stability-audit/run.sh`. The semantic inventory is intentionally root-directed and keeps
Scala signature closure separate from the command/wire inventory. It is evidence for promotion
decisions, not a stability freeze or release certification.

The verified run has 27 explicit roots, 781 reached symbols and 10,656 typed edges. It reaches
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
The adverse independent review and its three repaired cases are retained in
`adversarial-review-02b75c9b.md`: constructor-only parameters, term-shaped parents, and named
companion factories of reached result types.
The walker reports direct signature edges and their transitive type symbols, but does not prove
JVM ABI compatibility, bytecode bridge/default-getter ABI, or wire compatibility. Those remain
separate review obligations before a freeze.
