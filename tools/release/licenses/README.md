# CLI dependency license evidence

`catalog.tsv` binds the current runtime artifacts to the exact names declared by their
non-evicted sbt resolution records. Columns are Maven GAV, artifact SHA-256, declared license
name, evidence filename, evidence SHA-256 and source URL. Packaging requires a matching row
for every declared license on each external JAR. A dependency version or byte change requires
reviewing that binding before a new bundle can be staged.

The catalog currently covers 49 external artifacts and 51 declarations. Cats Collections'
MIT/BSD-3 declarations and JNA's LGPL/Apache declarations are both retained. The actual binary
JARs are copied intact; their embedded legal notices are also extracted byte for byte, including
vendored component notices such as Jackson's FastDoubleParser and ONNX's ThirdPartyNotices.
Executable files such as DJL's `License.class` do not count as text evidence.

The Apache and LGPL texts come from their official license publishers. Cats, Cats Collections,
Cats Parse, Anthropic and ONNX evidence comes from the corresponding version's upstream source.
Cats' complete COPYING file retains its incorporated notices. Jawn's evidence is the complete
initial license comment from `jawn/AsyncParser.scala` in the exact 1.6.0 Maven sources JAR;
the extraction retains the comment delimiters and adds one final newline. SLF4J evidence is its
original embedded `META-INF/LICENSE.txt`.

Standard Webhooks' Maven 1.1.0 sources match all four Java files at commit
`b57537b4939afb932bccfed18d6853ec6b6f0d81`; its license is the enclosing `libraries/LICENSE`
at that same commit. This avoids substituting the repository root's specification license or
the later 1.2.0 source. Retrieval, extraction, source-match and runtime-inventory receipts live
in [the S1 evidence](../../../docs/refactor/evidence/source-cli-20261015/).

The original third-party legal files are retained unchanged apart from Jawn's documented
comment extraction. The catalog records artifact associations; it does not replace those terms.
