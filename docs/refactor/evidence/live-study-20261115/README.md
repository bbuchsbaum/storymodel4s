# Live study execution (2026-11-15)

Ticket `bd-01M7GV17P8FYWQ2RAHMGEG12XQ`. Runtime candidate
`c4238aa3f3ed1e82b6e66890c661b822a0213404`, over recovered main
`f85761a5684f04693e66c8a26c4d39b0de3d73f6`.

[`agentStudyExecute`](../../../guide/live-study-execution.md) executes a checked study through
one existing OpenAI-compatible transport, records replies before parsing, limits new calls,
resumes completed exchanges and replays offline without credentials or a model client. Legacy
imported recordings retain their v1 identity and use the same mapping loop and bundle renderer.
New v2 exchange keys bind the effective endpoint, model, wire protocol and token budget.

A run descriptor binds the exact inputs and configuration. The live writer owns an exclusive
file lock and journals dispatch before making a request. Missing recordings with started or
failed journal entries refuse automatic repetition. Corrupt files, malformed journals and
dangling journal links refuse spending. A valid recorded reply can recover a stale started
entry. This is process-interruption recovery; it does not promise exactly-once remote execution
or power-loss durability.

Execution provenance is checked on bundle readback against an expected profile and per-session
call count. Mapping records and unit rows match offline; invocation accounting differs. A saved
invocation receipt proves completed execution. The receipt inside the atomically published
output proves publication. Required receipt persistence and session closure happen first.

## Scope and evidence

`tools/reference-scope.sh BASE CANDIDATE` exits 5 on declaration-free `StudyExecutionCli.scala`.
The recorded source-reference query and independent review agree on JVM-only `providerAgent`
and `pipeline`. No changed-symbol references were found in StoryAtlas sources. Both modules are
tested with CI fatal warnings, with clean test compilation for external construction probes;
format checks run last. The Python readers are also tested.

The local gate passed **88 provider tests and 595 pipeline tests**, with zero failures. The one
provider skip is the opt-in real-provider smoke court. Both format checks and **276 Python tests**
passed. All **125 legacy bundles / 21,100 unit rows** are byte-identical to the previous baseline,
with zero model calls. Three compiling mutants fail their named assertions; restoring the exact
source passes all 14 affected tests again.

The [qualification receipt](qualification.json) binds exact local commands, totals, log hashes,
compiled mutation controls and unchanged recorded-study outputs. The local HTTP fixture uses
only authored geometric events. It tests 429/500 retries, malformed-response recording, a call
budget stop, cached resume, credential-free offline replay, input preflight, receipt-write failure,
configuration changes, uncertain attempts and exclusive ownership. The existing Python reader
also reads the resulting live bundle, including its original unit key zero.

The [independent review](review.json) is bound to the runtime SHA. No participant material,
credentials, recording contents or model replies from private studies are included here.
Private evidence remains in ignored `data/.live-study/20261115/`.

## Qualification boundary

The original local qualification receipt above records the initial engineering gate, before an
external call was authorized. The subsequent [real-provider smoke receipt](provider-smoke.json)
qualifies the unchanged implementation through the actual study CLI against OpenAI, using
`gpt-4.1-mini-2025-04-14` and two authored synthetic geometric units. One HTTP request completed;
the provider reported 141 input tokens and 35 output tokens. Both units received their expected
assignments. A separate replay process, with provider credentials removed, made zero HTTP
requests and reproduced identical unit rows and mapping records. Whole bundle bytes differ
because live and replay call accounting differs.

This is a bounded engineering smoke, not a scientific accuracy result or a guarantee for other
models or providers. The older opt-in parser `LiveSmokeSuite` was not rerun; its skip remains part
of the original test totals. Raw synthetic inputs, recordings and logs remain private and ignored
under `data/.provider-smoke/20261115/`; only counts, hashes and configuration are published.

Hosted CI is separate from local evidence:
[Continuous Integration](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43781813788) and
[Documentation](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43781813869) test the runtime
candidate. All four CI matrix jobs and documentation verification completed successfully. The
run URLs, exact SHA and matrix cells are retained in the provider-smoke receipt. Pages build and
deploy jobs were skipped for the task branch; docs verification success is not publication.

## Landing

The final candidate `932e61f60ee81929837998ef78dad4b700667d21` passed independent exact-SHA
review, including the documentation and tracker-only delta from the qualified runtime. It
fast-forwarded main and was pushed with local/tracking/remote SHA parity. The pre-integration
ownership check found only this task's two live reservations and its claim; no competing owner
held a touched path. All 18 pre-existing unfinished StoryAtlas files retain their checkpoint
hashes.

The engineering ticket is closed and both reservations and the claim are released. The clean
standalone verification clone was removed after preserving its logs and synthetic artifact; its
exact runtime commit is an ancestor of main.

The scoped engineering acceptance, hosted matrix and bounded real-provider smoke are now
complete. The later qualification changed documentation and tracking only; the source remains
the exact qualified implementation.
