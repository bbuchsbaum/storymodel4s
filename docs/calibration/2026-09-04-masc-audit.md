# MASC participant-role corpus audit — 2026-09-04

**Decision: MASC is a useful diagnostic resource, but the audited release does
not supply our requested independent, multi-story participant-role calibration
set.** Its fiction/ficlet subset contains one PropBank-annotated chapter and no
FrameNet annotations. That chapter also fails a structural coordinate check.
The broader annotated material could support a separately declared mixed-genre
study; it does not become narrative calibration evidence by changing its label.

This audit examines annotation availability and import feasibility. It neither
fits a model nor judges semantic correctness. Participant-role reliability is
one uncertainty coordinate underneath the structured story/recall model.

## What was inspected

The complete MASC 3.0.0 archive, the separately distributed original PropBank
and Penn Treebank archives, and every MASC `.prop`/`.gold_skel` file in the
[official unified PropBank release](https://github.com/propbank/propbank-release/tree/4abade0b53ce4a181e1d98b3518101c1a44d395a/data/oanc/masc)
at `4abade0b53ce4a181e1d98b3518101c1a44d395a`.

- [Input manifest](2026-09-04-masc-inputs.json): acquisition URLs, archive hashes,
  and individual unified-annotation file hashes.
- [Inventory](2026-09-04-masc-inventory.json): all 392 document headers, source
  hashes, header word counts, exact-basename annotation matches, and refusals.
- [Reproducer](../../tools/audit-masc.py): offline inventory generation; archives
  are read without extraction. Output contains metadata/counts, not story text.
- [Checks](../../tools/test_audit_masc.py): five falsifiers and three executable
  mutations protecting the coordinate findings, with [bound command receipts](2026-09-04-masc-checks.json).

The ANC server's certificate was expired. Public, unauthenticated ANC downloads
were made with certificate verification disabled for those requests; PropBank
HTTPS verification remained enabled. The hashes identify the bytes inspected,
not an independently authenticated ANC distribution. Reacquire against a valid
certificate or an authenticated independent distribution before corpus admission.
The [official download page](https://www.anc.org/data/masc/downloads/data-download/)
and its downloaded metadata are recorded separately from the archive contents.

## Measured coverage

| Population | Files/documents | Role annotation found |
|---|---:|---|
| Complete MASC 3.0.0 | 392 | Coverage differs by annotation layer |
| Fiction | 7 | PropBank in `lw1` only; no FrameNet |
| Ficlet collections | 5 | No PropBank or FrameNet |
| Unified PropBank MASC release | 97 | 96 exact-basename joins to MASC headers; `wsj_1640` remains unmatched |
| Original PropBank archive | 100 | A different edition; five basenames unmatched to MASC headers |
| FrameNet in MASC `data/` | 22 | GrAF annotation files, all outside fiction/ficlets |

There are also 23 original-format FrameNet XML files in the archive. Original
and converted representations are not additional independent documents. The
inventory's FrameNet count refers specifically to the 22 GrAF files in `data/`.
Absence here is absence from these inspected archives and directories, not a
claim that no separate annotation resource could exist.

Among the 96 exact unified PropBank/header joins: 40 newspaper, 31 letters,
7 journal, 5 telephone, 4 face-to-face, 2 email, 2 travel guides, and one each
court transcript, fiction, government document, non-fiction, and technical.
A news or history document may contain a narrative; genre alone cannot decide
that. `chapter-10` is a government document, not evidence of a second fiction
chapter. `HistoryGreek` and `HistoryJerusalem` are the two annotated travel-guide
matches. Narrative selection from those genres needs a declared target and
source review.

### Fiction files

Word counts below are the distribution's header values, not token recounts.

| File | Header author | Header words | PropBank | FrameNet |
|---|---|---:|---|---|
| `cable_spool_fort` | Bill Glover | 1,644 | absent | absent |
| `captured_moments` | Will Shetterly | 5,409 | absent | absent |
| `easy_money` | Bill Glover | 2,925 | absent | absent |
| `hotel-california` | anonymous | 5,984 | absent | absent |
| `lw1` | Dee Dreslough | 730 | present | absent |
| `Nathans_Bylichka` | Foster A. Ranney | 10,466 | absent | absent |
| `The_Black_Willow` | Foster A. Ranney | 3,874 | absent | absent |

The `lw1` header identifies *Lost Waters — chapter 1*, written in 1994–1997.
It is one chapter of one narrative, not a set of independent stories.
The five ficlet files each contain 30 `ID:` records: 150 distinct pieces in all.
Their prequel/sequel metadata contains 222 numeric references, 169 inside this
sample and 53 outside it. These are directed metadata references, not unique
edges or story counts. Neither five files nor 150 piece IDs establishes how many
independent, complete narratives they represent. Leave-story-out grouping must
follow the linked narrative families, not the storage files.

## The `lw1` import court

| Measure | Original PropBank | Unified pinned PropBank |
|---|---:|---:|
| Predicate annotation rows | 133 | 131 |
| Numbered argument records (`ARG0`–`ARG4`) | 203 | 199 |

These are annotation-entry counts. They do not establish exhaustive predicate
coverage, distinct source spans, or independent human judgments. In the unified
release, the 199 numbered records comprise 66 `ARG0`, 104 `ARG1`, 26 `ARG2`,
1 `ARG3`, and 2 `ARG4`; modifiers, `rel`, and `LINK-*` records are counted
separately in the inventory.

The coordinate audit found three distinguishable axes/artifacts:

1. ANC sentence segmentation has **50** regions. The PTB/skeleton segmentation
   has **48** sentences. Sentence indices cannot be transferred directly.
2. GrAF PTB token annotations number **860**. The unified skeleton contains
   **861 physical token rows**. At zero-based sentence 26, token 2, its word
   placeholder and POS label are merged into one field: `[WORD]NP-SBJ`.
3. The bundled tree contains `(NP-SBJ *PRO*-1)`. Counting leaves after excluding
   explicit `-NONE-` nodes yields 861, but that convention mistakes the malformed
   subject for a surface leaf. The separate treebank changes this to
   `(NP-SBJ -NONE- *-1)`, with missing inner parentheses. A parser requiring
   proper branch/preterminal structure refuses it. It is not a validated repair.

An early exploratory leaf walker silently dropped the malformed branch and
reported 860 leaves for the separate tree. The independent cold review caught
that error. The checked-in reproducer now refuses that tree shape, retains the
malformed skeleton row, and refuses the overall structural join. Mutations that
restore each of those three failure modes are killed by named assertions.
Agreement of counts and POS tags, even if achieved, would still not establish
exact source-token or character-span alignment: the skeleton redacts words.

No source or annotation was repaired in this audit. A future importer must
resolve these artifacts with an explicit transformation receipt, retain the
original bytes, and verify every admitted token/span against the exact source.
There is no production MASC importer whose output this audit has shown corrupt;
this is an observed input incompatibility and a tested structural refusal.

The original archive also contains a six-field row in `wsj_0176.prop`, unlike
its surrounding seven-or-more-field layout. The inventory retains a parse
refusal for that document rather than presenting shifted-column role counts.
Potential filename aliases are listed, not automatically equated.

## What still prevents calibration

**Annotation meaning.** PropBank numbered roles are frame-specific. Our
`ParticipantRole` vocabulary is a separate semantic contract. Reusing the
prediction pipeline's role normalization to construct the reference labels
would leave errors in that normalization untestable. Frame-sensitive human
review must establish the mapping, and cases it cannot settle stay unresolved.
MASC's narrative subset provides no second FrameNet layer for comparison.

**Annotation provenance.** The official PropBank README describes adjudication,
quality control, retrospective frame unification, and some automatically
assigned auxiliary senses. That is useful upstream provenance, not a receipt
showing compliance with this repository's two independent human annotators,
separate adjudicator, blindness rules, or retained individual ratings. The word
`gold` in an annotation row does not supply those missing records.

**Training exposure.** Public release predates current parser models. This audit
has not established the deployed parser's training membership, performed the
protocol's contamination-risk procedure, or run its leakage control. Corpus
publication alone neither proves memorization nor proves independence. No
formal low/medium/high contamination class is assigned here.

**Target and coverage.** The calibration implementation needs final judgments
on the frozen pipeline's emitted situation–filler–role candidates. A positive
source annotation is not already a candidate correctness judgment. A missing
source annotation is not a negative label. No candidate extraction, independent
coordinate mapping, or human judgments were produced here.

**Admission.** MASC's [distribution description](https://www.anc.org/masc/About.html)
provides a promising rights lead. Individual headers still need the exact-source
and attribution checks in the [story-text admission checklist](../design/story-text-admission-checklist.md).
Corpus files remain external to the repository; this audit admits none of them.

## Next work, in order

1. **Keep MASC diagnostic.** Use `lw1` to specify an importer refusal/repair court
   if that importer is needed. Do not build a general MASC ingestion layer merely
   to obtain one story's labels. A mixed-genre diagnostic subset could be useful,
   but its population and annotation mapping must be stated separately.
2. **Prepare a narrow human annotation study.** Begin with a planning pilot of
   12–20 varied, independently authored short narratives with recorded rights
   and exposure history. That range estimates organizational scope, not
   statistical sufficiency. Annotate local situations, filler spans, normalized
   roles, context, and alternatives from the source under the existing
   [human adjudication protocol](../plans/2026-08-28-m1-fixture-adjudication-protocol.md).
   Two humans work independently; a third adjudicates. They do not see model
   candidates or scores. Retain their separate ratings and freeze the result.
3. **Map and measure before expanding.** Match frozen annotations to the exact
   pipeline's candidates, keep unresolved mappings, and inspect each role/scorer
   cell's support across stories. Separate whole narrative families into
   development, calibration, and untouched evaluation partitions before tuning.
   Expand according to coverage, uncertainty, and between-story variation.

The next deliverable is a blinded annotation packet, source manifest, and
recruitment specification for this narrow role study. Human staffing and source
rights must be established before collection. This role-only study does not
require a free-recall panel; it also does not satisfy the full recall study's
additional requirements. WOG remains outside fitting and tuning.

## Reproduction and evidence

Keep inputs outside the repository. Fetch the three archives using their URLs
in the input manifest, naming them `masc.tgz`, `original-propbank.tgz`, and
`treebank.tgz`. Clone the official PropBank repository into `propbank/` under
that input directory and check out the pinned revision above. The script checks
the archive and individual annotation hashes before counting.

```sh
python3 tools/audit-masc.py /path/to/external-inputs \
  --manifest docs/calibration/2026-09-04-masc-inputs.json \
  --output /tmp/masc-inventory.json
cmp /tmp/masc-inventory.json docs/calibration/2026-09-04-masc-inventory.json
python3 tools/test_audit_masc.py
python3 tools/test_audit_masc.py --mutations
```

The local acquisition directory is
`/private/tmp/storymodel-masc-audit-20260904`; its retention is temporary.
Reproduction is supported by the pinned URLs and hashes, not by that directory's
continued existence. Five baseline tests passed; three compiling mutants failed
named assertions without runtime errors. The independent review reproduced the
coverage and coordinate findings and corrected the early tree interpretation.
This evidence supports the inventory and refusal behavior, not an empirical
calibration result. Scala source and production defaults are unchanged.
