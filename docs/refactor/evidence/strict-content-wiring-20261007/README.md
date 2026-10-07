# Strict content wiring: S2a-3

Ticket: `bd-01M379N8HK5ZCV9W252X9MQ4VV`. Source base: `50903b18fa4d2b004de55134426f5bbaa6b2c783`. Code candidate: `a2d795b26e64ca9cf304c861216c7622bb807190`.

The canonical production path now uses a closed semantic channel (`Lexical`, checked `ContentTable`, or `Unavailable`) and `StrictCostModel` through candidate generation, local evidence and HSMM validation. Candidate evidence binds the actual immutable channel, rather than only its kind. Content-keyed tables cannot retain source IDs or arbitrary callbacks. Canonical member results are re-keyed to their originating source members.

Observed zero, provider abstention, channel unavailable and ineligible retain distinct typed generation outcomes, including when there are no ranked candidates. Missing semantic evidence uses the declared imputation and stays unmeasured. Ambiguous measured gates return their exact alternatives as a typed refusal to the current single-facet downstream model.

Historical inference and `hsmm/v4` encoding remain byte-compatible with the frozen goldens. Canonical inference is contextually revalidated under `hsmm/v5` with a required canonical gate tag. That tag certifies gate interpretation, not provider execution or reference-candidate eligibility: consumers needing generation provenance must retain the originating `LocalEvidence`. The wider S2a-4 scorer court and behavioural laws, remaining G1 reference/bundle integration and scientific calibration remain separate acceptance.

## Qualification

Code and test qualification is complete on the exact source and consumer candidates. Integration follows the independent review of the final evidence commit. `final-local-inputs.json` binds all 23 owned source inputs. The focused alignment/codec gate passed 94 tests, and both corrected historical golden fixture suites passed 10. Hosted source run [37613462330](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37613462330) passed on the exact source SHA:12,722 Passed,0 Failed,0 Errors,12 existing Skipped across80 totals (JVM17,JVM21,JS17,Native17). Its full logs and receipts are in `hosted/source`. Hosted documentation run [37613462315](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37613462315) passed on the exact source SHA, including all 13 executable examples; full logs are in `hosted/docs`. Formatting ran last.

All six mutations compiled and killed exactly their named witnesses while sibling controls passed. `mutations/summary.json` binds each patch, fresh JUnit report and full log to the exact source SHA. The restored clone passed 94 tests with no failures, errors or skips and was clean at the source candidate. Constructor probes were freshly recompiled.

`code-review.txt` records the independent code GO. The exact consumer `0cd43ffdb37e6a2c71577284368cec4cd823c921` has a separate independent code GO (`consumer-code-review.txt`). Its full default published-pin local gate passed 485 tests across 10 totals, with no failures, errors or skips; application linking, workflow generation and final formatting also passed. Hosted consumer run [37614267670](https://github.com/bbuchsbaum/storyatlas4s/actions/runs/37614267670) passed on that exact consumer SHA:753 Passed,0 Failed,0 Errors,0 Skipped across15 totals. Full job logs and receipts are in `hosted/consumer`. `qualification.json` records the complete qualification. Landing, publication and workstation parity are recorded separately in the source and consumer Mote tickets.

## Corrected failure

The initial hosted candidate `bd39565d015c27a3492cdc8a475a60af2f7bc4d3` failed two fixture assertions that listed only `hsmm/v4` as supported. The codec correctly reported both `hsmm/v4` and `hsmm/v5`. The final candidate updates those assertions; no golden bytes or numerical pins moved. `failed-jvm17-bd39565d.log.gz` retains the complete diagnosis. Canceled fail-fast matrix cells are not passes.

## Data and preservation

Only existing admitted and synthetic fixtures were used. No private input, transcript, data manifest or private archive was read or transferred. The unrelated source draft and 1,177 unrelated StoryAtlas files were checked byte-for-byte against their preservation receipts.
