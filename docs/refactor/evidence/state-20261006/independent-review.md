# Independent salvage assessment

Read-only reviewer: `/root/salvage_review`, 6 October 2026. Main examined:
`0500bc1029bea9df88291a7b73860f8fecfa804d`. No builds, file edits or tracker writes were made
by the reviewer. This is a fresh source/evidence assessment, not fresh test execution or landing.

All three candidates contain changes genuinely absent from main, with no source-level salvage
blocker found. Relevant paths have not drifted on main since the candidate bases.

1. Reference-scope `ed9f478daba90d1728ce584b2a896f5a88be9b26`: retain the bounded two-file
   declaration-free-source refusal. The earlier Fray request has no independent reply and its
   temporary raw-log directory is gone. The parent agent subsequently executed the archived
   candidate tools: 98 assertions passed, exit zero; see `scope-test.log` and `checks.json`.
2. FrameSet `69e48e9a370ee6a3c4ff42d11abca36e614e60f9`: retain streamIndex in full-frame
   receipt/identity and the v2-to-v3 algorithm bump. The discriminating witness holds geometry,
   digest, count and arguments equal while shifting the second stream PTS by 500. Identity must
   differ. Historical evidence reports 61 passed, zero failed, three skipped; the live ffmpeg
   check was skipped. The old independent review response/raw temporary logs are absent.
3. S2a-4 `e02d80d01d2a0ebba3bea8c4716caab9662209dc`: retain tests, mutation tools and evidence.
   Outside-package construction/access probes, JVM surface/signature checks and behavior laws
   strengthen the landed content seam. Corrected SemanticGraph/ContentCompatibilityReport
   probes replace stale eight-for-nine and seven-for-five argument shapes with meaningful
   same-shape controls. All twelve retained mutant source hashes were independently
   reconstructed and matched their receipts; logs have named failures, passing siblings and
   no compile-error substitution.

S2a-4 historical local gates: JVM 699 passed, JS 672, Native 672, zero failed/skipped; 74 focused
restoration tests passed; formatting ran last, exit zero. Source bound by those receipts:
`b2b700cb9b6192cdd58fe719c0738e65b478b385`; final `e02d80d` adds only evidence. Its README,
summary, commit message and Mote notes explicitly leave ChannelUnavailable versus
ProviderAbstained to S2a-3 (`bd-01M379N8HK5ZCV9W252X9MQ4VV`). Recovering the bounded slice can
precede that implementation; closing the complete S2a-4 ticket cannot.

The candidate is candid about controlled scorer behavior, reflection limits and stateful
callbacks. It does not qualify arbitrary user callbacks or the strict end-to-end pipeline.

## Stocktake commit review

The same independent reviewer checked `ed1b9d3fa5f2362987afcc1aab50a730507bbbee` against main
`0500bc1029bea9df88291a7b73860f8fecfa804d`. Substantive inventory, branch classifications,
ticket counts, all 205 recovery refs, bundle size/hash and preserved branch/stash tips matched.
The 1,658 bundle heads include pseudo-ref HEAD, agreeing with 1,657 named refs in the mirror.
All 345 changed paths were documentation or additive tracker operations: 292 historical and
30 audit operations, eight requeues and four assignment clears across eleven tickets, with no
product closures or acceptance/dependency edits.

Verdict on that commit: BLOCK. Exact `git diff --check 0500bc1 ed1b9d3` returned 2 because all
277 CSV lines used CRLF. The initial tracked-only working diff check omitted this new file.
The successor normalizes CSV line endings and records a full staged diff check against main.
Three wording FOLLOW-UPs remove residual active lead references and label the September handoff
historical. Technical/design, exact-SHA review and sibling-consumer gates remain required.

Successor verdict: GO on exact `7272c51c10736dcbef3988348c72fc6582ada32c`, with no remaining
BLOCK. The reviewer independently ran the full diff check against `0500bc1029bea9df88291a7b73860f8fecfa804d`:
exit zero, no diagnostics. All 277 CSV rows are intact; changes are CRLF-to-LF only. The corrected
receipt preserves the initial failure, all three wording findings are resolved, and the six-file
successor adds no product source or tracker changes. Earlier substantive findings stand.
