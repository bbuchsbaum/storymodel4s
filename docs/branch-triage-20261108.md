# Branch triage, 2026-11-08

`git branch --no-merged main` listed 91 refs (92 once this branch existed) at `main` 9b5a1f270. This
was a read-only audit; no ref has been changed yet. Each action below waits on the owner's go-ahead
(see the epic, `bd-01M6Y7MWZGP29TNDJ90SG36A45`).

## How the October branches stack

Three tips fork from `13aec7cc8`; `identity-feasibility` and the `integrate-codex` merge have
merge-base `c566de3ca`. `git cherry` finds no October commit already on `main`.

```
main@13aec7cc8
 └─ reality-check-plan 6702a28ec (22)
     ├─ instrument-score-evidence c24c49162 (+107)                         TIP
     └─ mapping-policy segment 56d750408 (+22)
         ├─ mapping-audit cc7219edb (+5)                                    TIP
         └─ scoring-evidence 0b738b4df (+31)
             └─ context-evidence → facet-assessment → connected-assessment
                 → evidence-binding-s0 → evidence-binding-s1
                     └─ integrate-codex f09066772 (merge with main@c566de3ca) TIP
main@c566de3ca
 └─ identity-feasibility 542c4a847 (+4)                                     TIP
```

## Decisions

| Ref | Contents | Action |
|---|---|---|
| `claude/integrate-codex` f09066772 | Scoring correction (semantic-only, source-conditioned), claim assessment, mapping summary v2, workload capture, selected policies, reference-only facade, codec wire v2, ADRs 0019/0022 text, connected/binding assessor tools, ~50k lines of evidence, ~470 `.mote` ops | **Merge**, after a full `checkAll` and the storyatlas4s seam. No textual conflicts are expected. Semantic hot spots: S3c/S3d in `align`/`pipeline` against `MappingPipeline`, `mappingrun` and `strictcost`. |
| `codex/mapping-audit-20261020` (5 commits d3c9543dc..cc7219edb) | Pinned downloads for MiniLM, Whisper variants, Parakeet, Qwen3-ASR and Qwen3-VL; `tools/model-assets.py`; guide | **Harvest** first; E1 needs these models. |
| `codex/identity-feasibility-20261025` b062895ec, 0f99a82c5, 8553e728a | Identity census tool, binding spec, direct-LLM-baseline amendment | **Harvest**; resolve the minor `PLAN.md` conflict by hand. |
| `codex/instrument-score-evidence-20261015` c24c49162 | Historical score reconstruction (~7k lines of tools, ~55k lines of evidence); `StageTrace` decision scores | **Archive**. Recover `StageTrace` decision scores later only if the E0 stage report needs them. |
| 8 intermediate October refs | Contained in integrate-codex | **Archive** once the merge lands. |
| 65 refs from before 2026-09-20; 21 refs from 2026-09-21 to 10-07 | Already landed, superseded or salvage | **Archive** as `archive/<name>` tags, then delete the branches. |
| `claude/mapping-decomposition-20261108`, `claude/consolidate-20261108` | Today's work | Land. |

## Losses

- The workbench clarity branches (`9481849ed`, `cb3cb1746`, `claude/workbench-hill-harness`)
  are gone: no object, ref or reflog entry survives.
- Two worktrees whose paths were under the deleted `.agent-work/` need `git worktree prune`.

## Open questions

1. Should `integrate-codex` merge with its evidence bulk, or as a squashed code-only harvest?
2. Should the remote-LLM assessor tools (OpenRouter, DeepSeek, Together) stay as the E2 starting
   point?
3. `codex/mapping-audit-20261020` is checked out in the primary tree; move the primary tree off it
   before archiving.
