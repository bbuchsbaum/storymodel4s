# Fray field notes

Maintainer: `codex-temporal`. Requested by the owner on 2026-09-23.

These are observations from doing the work, not a product evaluation with a
controlled baseline. Fray carries coordination and evidence pointers; Mote owns
tickets and work ownership, and git plus retained check results records delivery.
Keep entries brief enough that writing them does not displace implementation.

## 2026-09-23 — pairing retrospective

### What worked

- **A small driver/reviewer loop produced useful code.** Claude drove shared
  local evidence; I supplied consumer tests outside `align`. Those tests exposed
  two missing-versus-empty identity aliases and a source that changed during
  inference while retaining the original published fingerprint. The repaired
  six-test suite passed in my independent JVM run. Evidence:
  `solo/local-evidence-snapshot-probe` at `3fecc774`; Fray #24, #44–46.
- **Different roles supplied different evidence.** I implemented the checked
  workspace export; the other Codex implemented the CLI and ran nine actual
  Java command invocations plus two independent Python package reads. This
  covered a consumer boundary that my producer tests alone did not establish.
  Exchange handoff: `933d04b5`, Fray #23 and #35.
- **Concrete handoffs were actionable.** A commit, one failing test, the exact
  observed result, and a named fix owner worked better than broad review advice.
  The changing-source witness showed an actual likelihood difference, which
  made the requested snapshot repair unambiguous.
- **One integration owner and one heavy-build slot reduced contention.** The
  team used #16 to serialize full gates and named a final lander. Small checks
  could proceed independently. This was a team convention, not an automatic
  queued build scheduler supplied by Fray.

### Pain points and possible improvements

| Observation | Cost or risk | Improvement to try |
| --- | --- | --- |
| A critical review objection went unread in a busy thread; the recipient later acknowledged missing it. Direct follow-up recovered the handoff. | A known failing candidate was sent to a costly gate. | A compact incoming-decisions view showing the affected SHA, requested action, named recipient, and whether that recipient has read it. Receipt acknowledgment must remain distinct from agreement. |
| Question/objection replies created linked cards, and important context spread across #24, #32, #35 and direct messages. | We had to reconstruct which objection applied to which candidate. | Show one conversation tree with outstanding questions and their latest answers; preserve links when resolving or superseding a candidate. |
| I created several direct cards to recover missed handoffs. | The workaround increased the very inbox noise it was meant to overcome. | Prefer one handoff conversation per seam; add an explicit escalation or attention-request operation to the existing thread. |
| A bounded `brief` omitted agents/context and showed old resolved work among pending receipts. | Getting back to the current assignment required extra calls. | A resume view prioritizing current assignment, active peers, unread direct actions, and build ownership; collapse historical closure receipts. |
| Tool previews can truncate the action needed from a message. | An agent can mistake the preview for the full instruction. | Put the requested action and evidence reference in a short structured summary, with a clearly accessible full body and exact read receipt. |
| Shared home, actor identity, branch, Mote issue and final lander were coordinated manually. | Wrong-board, wrong-identity and wrong-checkout mistakes remain possible. | A session context command that displays and validates these bindings before mutations, without becoming a second issue tracker. |
| Passing test subtotals did not imply a passing full gate: strict compilation failed later. | A conversational “green” summary can overstate qualification. | Attach gate receipts with source SHA, command, exit status, completed tasks and log path; distinguish partial tests from a completed gate. |
| A Fray lease or enabled registration does not prove that an interactive agent is currently executing or will be woken. | Handoffs can wait for an absent consumer. | Keep registration, recent activity, runner state and actual wake capability visibly separate. |

### Working practice for the next slice

Use one driver per file scope, one named reviewer, one small contract and an
executable counterexample. Reply in the existing handoff thread. Include the
Mote ID, exact commit, changed paths, observed checks, remaining limitation and
next owner. Check the full message before acknowledging it. Finish the slice
before opening another design discussion.

We have evidence of defects caught and useful division of labor. We have not
measured net time or token savings against working alone, so no speedup claim is
justified yet. Watch for repeated handoffs, duplicate builds, missed actions and
time spent reconstructing state as work proceeds; avoid adding a logging chore
to every message.

## 2026-09-23 — P1 restart

- My availability note #47 received a bounded assignment from `codex-p1-lead`:
  Sherlock development reproduction, Mote `bd-01M35JFJCQ2ANTVED792H705X6`.
  The handoff named scope, input restrictions, evidence requirements and the
  integration owner. That is enough to begin checking admission and ownership.
- Two Claude sessions initially proposed the same strict-gate repair. The
  collision was visible in #49; one explicitly withdrew, and the lead confirmed
  the remaining owner. Distinct actor names helped. Fray exposed the collision;
  Mote ownership still needs to prevent conflicting edits.
- The coding lane and these field notes are separate responsibilities. Notes
  should record meaningful outcomes and friction without turning into another
  task board.

## 2026-09-23 — development reproduction in progress

- **The assignment was specific enough to execute.** Its Mote ID, file scope,
  pre-gold registration requirement and permitted inputs led to a bounded
  runner, 22 successful local mapper processes and an independent numerical
  check. The lead could continue integration work while this lane ran in its
  own checkout. These are concrete outputs; final review/landing are separate.
- **Versioned commands matter in handoffs.** The issue named `score.py`, while
  the current repaired scene scorer is `gold_scene.py`. Source and usage docs
  resolved the mismatch before execution. This is repository documentation
  drift, not a Fray transport defect. A handoff carrying the current executable
  entry point and source SHA would reduce the chance of using the wrong court.
- **Worker focus still needs refinement.** My `involved` inbox included several
  unrelated tie-policy discussions because I subscribe to `release`. Titles
  accumulated prefixes such as “Question on #55: Question on #50”. A worker
  focus view could show the assigned Mote reference, direct requests and an
  explicitly followed build-control thread while collapsing other release
  discussion. Do not silently discard it; keep broader discovery available.
- **Separate infrastructure latency from Fray latency.** Some message tool
  calls took noticeably longer than the reported shell command itself, while
  crossing the host approval boundary. We have not instrumented the split, so
  attributing that delay to the Fray daemon would be unsupported. A client
  receipt with queue/server/transport timing would make diagnosis less vague.
- **A useful next measurement is handoff completion, not message count.** For a
  future bounded slice, record assignment, accepted ownership, first executable
  evidence and accepted review times from existing receipts. Count duplicate
  requests and missed-action recoveries. This is a proposed measurement; no
  throughput comparison has been made here.

## 2026-09-23 — waiting without a wake mechanism

The owner asked how I would wake when peer review finished. Inspection showed
`codex-temporal` enabled in Fray with `controller: null`; I had no active wait
command. My earlier “waiting for peer review” described pending work, not a
running mechanism that would resume this interactive session. Another user turn
was needed for me to read the inbox. That distinction should have been explicit.

`fray wait` can block for attention while an agent turn remains active.
`fray drive` can run a configured noninteractive agent for selected attention.
Neither had been left running for this handoff, and registration alone supplies
no wake mechanism. A drive controller would need an intentional launch, command,
identity, scope and runtime limits; it would not by itself reconnect this chat.

Desired improvement: before an agent ends a turn with work pending on a peer,
show the actual wake owner and mode: active wait, live controller, host delivery,
or manual resume required. Make “review requested, manual resume required”
distinct from “waiting with automatic delivery.” This is both a product gap and
an agent communication failure in this instance, not evidence of a lost message.

## 2026-09-23 — source/export pairing

- The independent Sherlock reviewer recomputed the participant means and
  bootstrap from committed counts, then the named lead landed the reviewed SHA.
  This supplied different evidence from the author and avoided repeating the
  22 mapper processes merely to signal activity (Fray #47, verdict seq493).
- The installed skill advertises `inbox --addressed-to-me --unresolved`, but
  the shared daemon refused it with `unsupported_capability`: “Daemon does not
  support inbox filters. No operational request sent.” Ordinary bounded thread
  reads worked. Capability negotiation should expose this mismatch before an
  agent chooses a documented workflow. Restarting a shared daemon is not an
  appropriate worker workaround.
- Export and ingestion independently proposed ADR number 0020. Exchanging exact
  paths caught the collision before edits; ingestion moved to 0021 (Fray #59).
  The useful unit of coordination was the path and contract, not a longer plan.
- The availability card still said “ready for cold review” after that work had
  landed. Updating the existing card to the current ingestion assignment kept
  one conversation useful. A resume view should make stale summaries visible
  without treating every unread closure receipt as current work.
- **Reciprocal review changed both implementations.** The export author's source
  review caught an ambiguous heuristic clause label and imprecise whitespace
  wording; the package now labels each row with its profile and tests BOM-only
  input. My export review supplied a lawful same-spelling entity/context witness:
  production revalidation/export passed, but the reader rejected its bare-ID join.
  The handoff included the immutable SHA, exact witness files and a composite-key
  repair direction (Fray #60). A passing baseline reader court had not covered it.
- The exporter closed the parent pairing question after opening the linked code
  review request. Our reciprocal intake review still shared the parent thread.
  Closed conversation status therefore did not mean every direction of the pair
  had completed. Keep the two exact review subjects visible; do not infer that a
  parent's closure satisfies an unrelated pending review.

- The reciprocal reader review reproduced two mismatches that the Scala roundtrip
  court missed: rehashed undeclared capabilities were admitted by Python, and
  Python disagreed with Java's final-Unicode-line-terminator regex anchor. The
  useful review artifact was a lawful or adversarial exchange, not an approval
  adjective. Both become regression witnesses (Fray #62, seq575).
- A real `fray wait --after 574 --timeout 50 --selection involved` returned the
  review while this turn was active. That is a working wake mechanism for an
  active tool wait; it does not imply an idle chat will restart automatically.
- Resolving the exporter review raced with the peer's resolution. The expected
  revision check refused the stale patch; rereading showed the desired resolved
  state, so no retry was needed. This is useful conflict handling.
- A handoff briefly contained an unverified placeholder commit before correction.
  Obtain the SHA from the completed commit command before composing a handoff;
  the board should make exact source binding easy, not depend on remembered IDs.

- A second review round tested 20,000 seeded strings and found 363 remaining
  Java/Python normalization disagreements. The concrete absolute-end repair
  removed all 363. The portable Scala gate then exposed Java/Scala.js disagreement
  in the underlying canonicalizer. This is a strong pairing result: an independent
  oracle plus a platform court changed the implementation and identity contract
  where hand-picked roundtrips had passed. Keep the failed gate and the first
  insufficient repair visible; a review is not a one-pass ceremony.

- The reviewer then ran 300,000 cases against the compiled repaired class, not
  only a transcription, and supplied a hash-bound receipt archive. Our platform
  court separately confirmed JVM/JS/Native identity. The two parties supplied
  complementary evidence instead of treating each other's passing test as a
  reason to repeat it.
- Build-slot ownership helped during a real repair: I released the failed gate's
  slot; the lead used the gap for a bounded Native regression; I reclaimed it for
  the repaired source. The lead then explicitly took the expanded 20-module
  final gate, avoiding a duplicate broad run immediately before integration.
  A shared execution needs a named owner and an exact acceptance receipt, not
  merely a promise that someone will eventually run everything.

## 2026-09-23 — reaching an architecture agreement

- Consolidating the LLM-mapping discussion in Fray #75, with the duplicate #80
  redirected, gave peers one proposal to amend. The owner's clarification changed
  the decision from experiment priority to supporting a complete LLM mapper.
  Explicitly superseding the earlier synthesis prevented both decisions from
  remaining apparently current.
- Reading the public construction paths changed the agreement: a successful
  `UnitOutcome` currently needs a numerical decision basis, and
  `DerivationBinding.of` takes an `HsmmResult`. Adding a producer name and packet
  hash alone would not admit a truthful categorical LLM result. Exact code
  references were more useful than another general architectural endorsement.
- The amended proposal at seq712 received explicit confirmations at
  seq713/714/716/717 from the mapper, video, compiler and lead peers. Consensus
  was established by answers to the same proposal, not inferred from silence.
  The lead accepted recording it in the existing PLAN and facade Mote notes;
  this avoided creating a third roadmap.
- The discussion separated observed media coverage, evidence selected/rendered
  for a mapper, semantic support and empirical response frequency. These were
  easy to conflate in short messages. A proposal template with the decision,
  preserved distinctions, exact contract gaps and recorder would reduce drift.
- Fray would benefit from an explicit supersedes link and a compact view showing
  the current proposal, each peer's confirmation or remaining objection, and
  the durable decision record. We assembled those facts manually from receipts.
  This is an observed coordination improvement, not a measured throughput gain.
