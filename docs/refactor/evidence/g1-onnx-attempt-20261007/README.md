# G1 / S2b-1: concrete ONNX attempts and exact in-memory replay

Code: `d7c0d97a37a6a1ae375f7f12f250e8f7317f591f` over
`acc5cca1f0d62418c79baa96c09612b21153e758`; child
`bd-01M4BQWQN7DDCZT095ND23772A` of G1 `bd-01M2TACM78289S4TECE91GT5K2`.
This completes a bounded prerequisite. G1 and registered strict embedding remain open.

## What the capability establishes

`OnnxSentenceEmbedder.record(batch)` alone mints a non-case `RecordedBatch` after the
concrete, artifact-checked adapter returns. The record retains immutable actual model/artifact
configuration, provider/runtime/geometries and the original outcomes/attempt receipt. It
privately retains the exact ordered requests, including payload sensitivity and sanitized-payload
identity. There is no factory accepting arbitrary results, receipts or a generic embedder callback.

This establishes an adapter attempt, not successful native inference. Empty, keyless-denied and
entirely too-long batches remain distinguishable from observed vectors. Replay checks the expected
complete model configuration and provider, ordered IDs, requested geometries and exact payloads.
It returns the original outcomes and receipt after the encoder closes, makes no call and renews no
policy approval. Replay refusal enums and record diagnostics contain no submitted text. The
record adds no plain digest of non-public requests or vectors and owns no native resource.
Scala construction/privacy courts establish the Scala consumer boundary described by rule 8;
this is not a JVM reflection security guarantee or a wire replay format.

The new tests use the existing four-dimensional, project-authored ONNX/tokenizer fixture. This
qualifies the attempt/replay boundary; it does not execute the production MiniLM model, open
participant data, reproduce Sherlock, establish scientific efficacy or certify strict-reference
eligibility. Existing embedding behavior and benchmark entrypoints are unchanged.

## Discriminating controls and mutations

The 16 new tests cover exact replay after closing the encoder, observed known fixture vectors,
text/sensitivity, ID/order/length, role geometry, expected provider and model configuration,
entirely too-long and empty attempts, keyless denial and structural equality. A denied-request
control has identical original receipts but different private text; the records remain unequal,
replay refuses the changed text, and their public hashes remain equal. Receipt equality alone
cannot bind denied requests.

Outside `storymodel4s.embed`/`onnx`, each constructor, constructor-call/apply, copy, product
reconstruction, product mirror and private-read negative court has a same-shape positive control.
`mutations.json` binds nine compiled named kills and their passing controls to the exact source.
The five replay guard mutations each execute nine tests: one named failure and eight controls;
one additional declared test is explicitly filtered. Constructor-scoped, payload-read, product
mirror and payload-hash counts are retained separately, including filter-ignored counts.

`constructor-discovery.json` retains the first unfiltered public-constructor mutant: both the
constructor and Scala 3 constructor-call syntax become available, producing two named failures
and four passing controls. Its scoped follow-up has one named failure/four controls and a fresh
Test compile, reusing the identical previously compiled mutant source hash. Two local driver
expectations stopped during this evidence collection; the source was restored each time. The
first assumed only one constructor door would open; the second assumed Zinc would recompile an
identical mutant. These are retained evidence-handling corrections, not production changes.

Patch bodies and raw logs are gzip-compressed without altering their decompressed bytes. Each
patch applies to the exact code commit. Metadata retains executable argv, working directory and
actual child exit. For named filtering, use [MUnit's documented argument syntax](https://scalameta.org/munit/docs/filtering.html).
Restore the source and clean `embedOnnx` after a compile-time boundary mutation: macro expansions
must be recompiled against the restored definition, rather than reused from the mutant.

## Qualification and reproduction

Read `local-gate.json`, `hosted/source/receipt.json`, `hosted/docs/receipt.json` and `source-review.txt`
for completed exact-revision qualification. From this evidence directory, run
`python3 verify.py`; the repository must retain the exact code object to reconstruct each mutant
without editing the checkout.

| Gate | Passed | Failed/errors | Existing skips | Scope |
| --- | ---: | ---: | ---: | --- |
| Local cold restored | 5,198 | 0/0 | 2 | 23 cells: 11 JVM, 6 JS, 6 Native |
| Hosted exact source | 12,754 | 0/0 | 12 | 80 cells across four matrix jobs |
| Hosted docs | 13 examples | 0 | — | Executable examples and site/provenance checks |

[Source CI](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37664410336) and
[docs CI](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37664410358) bind the code SHA
above. `handoff.json` records the closed child, open parent and unchanged 112-ticket unfinished
queue (26/24/62). `code-publication.json` binds GitHub and approved-host code synchronization;
`publication-recovery.json` retains the first helper stop after GitHub moved, followed by the
verified idempotent workstation sync. The documentation/tracker successor is reviewed under T2;
its own GitHub/workstation publication is verified separately after that commit exists.
`reference-scope.txt` and `gate-command.json` bind the actual required command. The conservative
name search includes eleven JVM modules and their six portable JS/Native counterparts; all are
gated serially with fatal warnings and formatting last. No build/dependency settings changed.

The first restored-tree gate is retained as `platform-gate.log.gz` and
`incremental-probe-recovery.json`: after incremental restoration, the fromProduct/product-mirror
courts still contained expansions compiled against the case-class mutant. The source tree was
clean and non-case. The definitive `platform-gate-clean.log.gz` starts with `embedOnnx/clean`,
recompiles Compile/Test and runs the full scope again. Acceptance relies on this cold restored gate.

StoryAtlas consumes view/fixtures/codec, not embed-onnx. This additive adapter method changes none
of its consumed types or its source pin, so no consumer repin is required. `preservation.json`
binds the unchanged unrelated source draft and 1,177 pre-existing consumer paths. No private data,
credentials or machine session state were moved or published. Git/Mote publication and clean
approved-workstation synchronization are recorded separately from code/test qualification.

## Remaining G1 acceptance and next action

Registering a strict channel still requires checked bound surface-text rendering, actual
model/configuration/render/candidate receipts and identical immutable nomination/pricing.
Current `UnitContent`/`TargetContent` keys omit submitted text; joining raw-text scores to these
keys would alias differently ordered strings. Existing cosine distance spans `[0,2]`, while the
strict fixture table accepts `[0,1]`; the registered contract must explicitly preserve or transform
that scale and qualify historical preset parity. No silent clamp, lexical substitution or caller
receipt grants strict execution/reference authority. StageTrace must then adopt the originating
shared evidence without relabeling refined emissions as base costs. These remain on G1.
