# storymodel4s-provider-parser

JVM-only contract for external text-to-AMR parser services. This module does
not ship a model, download a checkpoint, or claim corpus coverage.

The boundary is deliberately asymmetric:

1. `ParserSentenceInput.fromAtlas` is the only source of parser inputs. It
   binds the exact sentence text and sentence-local token IDs, spans, and text.
2. A `ParserTransport` exchanges a versioned JSON envelope with an external
   subprocess or service.
3. `JsonAmrCandidateProvider` validates response IDs, the model-observed
   normalized input, and tokens independently echoed from parser state. A
   proposed item must use result schema v2 and declare an
   `ExplicitIndexListV1` marker sidecar. One row per rendered marker records
   occurrence ordinal, provider node ID, and the original token-index vector.
   Admission requires exact occurrence order, nonempty sorted unique in-range
   vectors, and equality with the explicit PENMAN marker before strict
   `AmrCandidates.fromPenman` conversion.
4. Every input receives one `ParserAttempt`: checked and aligned
   `PropositionEvidence`, or a typed recorded failure. Success requires a real
   `ProviderCall` or an unforgeable cache witness bound to the same request,
   proposal, and source receipt; parser work never invents an agent prompt
   receipt.
5. `CachingParserProvider` alone mints cache witnesses from admitted successful
   attempts. External cache implementations can retain and return those values
   but cannot construct or rewrite them. Every hit is revalidated and makes no
   transport call on a complete replay. `ParserDeterminism` compares canonical
   chart and alignment identities across uncached executions.
6. `SentenceIsolatingParserProvider` is the conservative batch-fault boundary:
   it invokes the delegate once per sentence so a materialization crash cannot
   erase a valid sibling. A real adapter may replace this baseline with
   deterministic bisection only if it preserves the same outcome-invariance
   court and records every provider invocation.

A runtime is either `Ready(PinnedRuntime)` or `Unavailable(reason)`. The ready
state requires source, package, checkpoint, base-image, and declared dependency
artifacts with exact checksums and licence evidence. Unavailable runtimes fail
closed before any transport call and distinguish unpinned dependencies,
missing artifacts, platform incompatibility, and insufficient measured host
resources. The runtime's scalar version is the external wrapper version; it,
the artifact set, parser configuration, and input digest all participate in
content-addressed request and cache identity.

Rendered IBM ISI markers are not authoritative evidence: two-number markers
denote ranges, and the renderer can collapse or expand structured alignments.
The external wrapper must therefore render the declared explicit-list dialect
from its post-remap alignment state and return the structured vector alongside
it. Provider node IDs are retained as provenance, while rendered-marker ordinal
is the join key so literal/attribute targets are covered even when their
internal node IDs are not PENMAN variables.

The test fake can succeed, time out, fail batch materialization, return
malformed JSON, corrupt IDs, lie about model input or independently echoed
tokens, emit lossy ISI markers or a reordered sidecar, and change its second
result. These tests establish the contract algebra only. The next slice must
bind a real runtime and publish real WOG and held-out coverage ledgers; this
module does not claim that a checkpoint has been installed or executed.
