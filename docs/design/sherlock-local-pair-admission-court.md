# Sherlock local-pair admission court

**Bead:** `bd-01M1CH1SW9K3ZW8D0HBTH9JVRP`

**Inspection date:** 2026-08-31

**Target status:** `CanonicalExternalVideoRecallTarget`

**Raw-video storage status:** `ExternalOnlyNoRedistribution`

**Non-video artifact status:** `ShareableAfterApplicableAdmissionChecks`

**Scientific-validation status:** `IntakeUnvalidated`

## Disposition

The locally held Sherlock video, coder annotations, and timestamped-recall
collection are the canonical materials for the first video-to-recall vertical.
Only the actual episode video file contents are permanently external: they must
not enter Git or be redistributed. Media hashes, stream metadata, and identity
receipts are shareable. Annotations, recall transcripts and exports, aliases,
transforms and receipts, graphs, features, reports, model outputs, and other
Sherlock-derived data may be committed and shared after their applicable
scientific provenance and story-text admission checks.

War of the Ghosts remains the public, researcher-reviewed narrative acceptance
fixture. Sherlock has a different but equally canonical role: the external,
locally realized video-recall target against which the film workflow is built
and validated. Its coder atlas is a diagnostic proposal source, not direct
episode truth, independent gold, or calibrated validation.

Chen et al. (2017) report the original Sherlock viewing and unguided-recall
experiment; the public ContextLab release states that it contains the
annotations and recall transcripts analyzed in the later Heusser et al. work;
and the J. Chen Lab Zenodo v1.3 release publishes word-timestamped Sherlock
recall transcripts. The owner's project policy is recorded in board decision
`post-01M1CN0CHHMYSA93R7AZMPZQB2`: the obsolete new-licence, new-consent, REB,
participant-prose, and aggregate-only holds do not apply to these non-video
Sherlock artifacts. This is not a legal adjudication or a claim that a film
licence was obtained, and it establishes neither semantic authority nor
scientific validity.

## Independent status axes

These axes must not be collapsed into one “available” or “approved” flag.

| Axis | Current state | What would change it |
|---|---|---|
| Target selection | `CanonicalExternalVideoRecallTarget` | Sherlock is the fixed external target for the film vertical. |
| Local byte presence | `RealizedLocal` | Content-free byte and stream facts below establish presence only. |
| Raw episode video contents | `ExternalOnlyNoRedistribution` | The actual video files remain local under root `/tmp/`; they never enter Git or a distributed artifact. |
| Media identities and metadata | `Shareable` | Media hashes, stream metadata, and identity/alignment receipts may be committed and shared without the episode bytes. |
| Published study basis | `Chen2017AndReleasedDerivatives` | The primary paper and public derivative releases establish the scientific source family, not equality of every local artifact. |
| Annotations, recalls, and derived artifacts | `EligibleForAdmission` | They may be committed and shared after the applicable provenance and story-text admission checks. |
| Media edition binding | `LocalBytesPinnedStudyEditionUnconfirmed` | Local media identities are fixed; equivalence to the exact experimental presentation edition remains to be tested. |
| Annotation transformation | `LocalArtifactPinnedTransformUnreproduced` | The local TSV is fixed, but the workbook-to-TSV transformation has not been reproduced. |
| Recall transformation | `PublishedReleaseLocatedLocalMappingIncomplete` | The public v1.3 source is identified; the local CSV-to-source mapping has not been checked end to end. |
| Alias identity | `Incomplete` | A checked mapping must distinguish all 17 source exports, the omitted alias, and the shifted numbering. |
| Semantic authority | `CoderAtlasDiagnostic` | A separate scientific decision could authorize a narrower claim; the present atlas does not establish episode truth. |
| Scientific readiness | `IntakeUnvalidated` | Source binding, coordinate, semantic, and comparison courts remain open. |

Local-use authorization permits the scientific work to begin. It does not make
artifact identity, coordinate alignment, semantic authority, or a validation
result true by default.

### Axis non-inheritance court

The following counterfactual mutations make the independence claim reviewable.
Changing one coordinate must not silently change another.

| Counterfactual mutation | Independently unchanged coordinate | Required result |
|---|---|---|
| Record the owner storage/sharing policy | Media edition, transforms, artifact admission, and scientific readiness remain unresolved | The correct gates are selected; no artifact or scientific claim is admitted by policy alone |
| Exclude the episode video contents | Media hashes, stream metadata, and identity receipts remain shareable | Video bytes stay external; metadata is not suppressed with them |
| Locate a public release | Equality of the local exports to that release remains unchecked | Source family identified; local mapping still incomplete |
| Pin both local media byte identities | Exact experimental-edition equivalence remains unconfirmed | Canonical local edition fixed; study-edition claim withheld |
| Reproduce the annotation transform | Recall transform and alias identity remain incomplete | Annotation derivation checked; recall mapping still incomplete |
| Complete every identity and transform check | Coder atlas remains diagnostic and the comparison court remains open | Scientific readiness remains `IntakeUnvalidated` |
| Admit an annotation or recall artifact | Other artifact classes retain their own applicable checks | Admission is per artifact, not inherited across the bundle |
| Pass a predeclared scientific comparison | Raw-video storage remains `ExternalOnlyNoRedistribution` | Result may be reported; episode video contents remain external |

Any future machine-readable intake record needs mutation tests for these cases.
A single “approved” or “canonical” flag would make the rows indistinguishable
and is therefore not an acceptable representation.

## Content-free realized inventory

No participant-level filename, recall digest, transcript text, event label,
frame, word, or annotation excerpt is recorded here.

| Local class | Content-free identity and aggregate facts | Present limitation |
|---|---|---|
| Media part A | 285,537,456 bytes; SHA-256 `eb0364748e4bc6f66f43a84ab53e82792ac27dda48e9958bd5ba652c82b3ecca`; H.264 video and AAC audio; 640 x 360; 25 fps; stereo 44.1 kHz; duration 1,426.2 s | The canonical local bytes are pinned and remain external; their hash and metadata are shareable; exact experimental-edition equivalence remains unconfirmed. |
| Media part B | 315,565,018 bytes; SHA-256 `f6e2c839d355f86709379d74d28f24d89b919e45e0ac43771d197843ae694907`; H.264 video and AAC audio; 640 x 360; 25 fps; stereo 44.1 kHz; duration 1,554.8 s | The canonical local bytes are pinned and remain external; their hash and metadata are shareable; exact experimental-edition equivalence remains unconfirmed. |
| Coder annotation derivation | 225,692 bytes; SHA-256 `8c205826dcea8c58db24d7a17c71a3f99a9054e9379435e60b1dd0b987c2b296`; 1,000 data records and 23 columns | The local TSV is eligible for admission; its conversion has not been reproduced and its semantic authority remains diagnostic. |
| Timestamped recall collection | 17 source exports; 1,450–5,899 records per export; aggregate Princeton-clock extents 652.7–2,735.5 s and OpenNeuro-clock extents 660.2–2,743.0 s | The published source family is established; individual exports become shareable only through their ordinary story-text and provenance checks. |
| Quarantined aliases | 16 aliases are byte-identical to 16 source exports; the alias sequence omits one source and shifts numbering | They remain quarantined pending a checked identity mapping; after that check, the applicable artifacts may be admitted and shared. |
| Published recall source | Release tag `v1.3`, commit `ea87e76`, Zenodo DOI `10.5281/zenodo.8208709`; upstream tracks workbook sources | The local CSV mapping has not yet been reproduced end to end. The nested checkout is a local scientific input, not a repository dependency. |

The three recorded SHA-256 values identify the two media objects and the coder
annotation derivation and are shareable. They do not establish
experimental-edition equivalence or scientific validity. Before a recall
artifact passes its applicable admission check, any local identity for it must
use `SensitiveDigest` with an authorized keyed context; an admitted artifact
uses the canonical identity declared by its admission record.

## Timebase and edition findings

The current files expose multiple coordinate systems that must remain distinct:

- source-video identity and part-local media presentation time;
- coder-atlas row identity and part-local annotation seconds;
- scanner/run clocks, including run-local seconds and TR coordinates;
- any explicitly repaired or stitched elapsed-time coordinate;
- the timestamp systems carried by the two recall-export lineages;
- a future edition-specific playback axis.

The annotation contains two run-break records without TR values. Its seconds
reset after the first run. The second annotation run ends at 1,544.0 s, while
media part B lasts 1,554.8 s, leaving a 10.8 s media tail. These are observations,
not permission to repair the clock, impute TRs, trim media, or assert alignment.
Any stitched coordinate requires an explicit derivation recipe and receipt.

The intended two-part ordering is sufficient for planning but does not prove
that the local encodes are the exact presentation edition used to generate the
annotation or recalls. A future admitted `SourceBundle` must bind full source
identities, stream identities, relations, axes, and alignment receipts rather
than relying on filenames or order.

## Artifact-class storage and sharing

The current local materials and generated manifests remain under the
repository-root `tmp/` tree until an artifact-specific admission step moves or
copies an eligible non-video artifact deliberately. The root-anchored `/tmp/`
ignore rule makes accidental Git admission fail closed.

- actual episode video file contents remain under `tmp/`, must not be committed,
  uploaded as a project artifact, or redistributed, and are never admitted by
  a downstream scientific result;
- media hashes, stream metadata, and identity or alignment receipts may be
  committed and shared;
- annotations, recall transcripts and exports, aliases, transforms and
  receipts, graphs, features, reports, model outputs, and other non-video
  derivatives may be committed and shared after their applicable provenance
  and story-text admission checks;
- no new Sherlock-specific licence, consent, REB, participant-prose, or
  aggregate-only hold applies to those non-video artifact classes;
- do not delete or normalize the shifted aliases before the checked 17-source
  mapping explains their identity.

## Workflow role and validation claims

This court integrates Sherlock into the development workflow without widening
the present slice:

1. **B0 — artifact-specific intake.** Freeze the canonical target, raw-video
   exclusion, shareable media identities, per-artifact provenance and
   story-text admission, source bindings, derivations, segmentation populations,
   and coordinate semantics independently. B0 is not blocked on the superseded
   Sherlock rights or participant-content restriction.
2. **P1 — existing real-transcript gate.** P1 remains an independent dependency
   of the movie vertical; selecting Sherlock does not satisfy it.
3. **C1 — typed source representation.** After the B0/P1 governance gates, a
   separately authorized bead may construct the checked film source atlas and
   timebase-repair receipt required by ADR 0007. This court creates no API or
   adapter.
4. **D0 / D1 — acquisition and binding.** Film-description proposals require the
   second evidence-binding stage before narrative claims can be accepted.
5. **E0 — scientific comparison.** The first Sherlock result may claim only
   agreement with the coder atlas under a predeclared question. It must use a
   `SourceFrozen` or cross-fitted regime as appropriate and report refusal and
   coverage, not silently promote diagnostic labels to truth.

`TopicHmmEvent30` is recall-tuned diagnostic expansion, never independent gold.
It cannot choose a representation and then validate that same representation.
The first comparison must keep frozen representation choices separate from
diagnostic expansion and later cross-fitted exploration.

Playback is a research inspection aid, not scientific validation. A coherent
video overlay cannot establish source-edition identity, timebase correctness,
semantic authority, or recall alignment.

## Scientific integration gates

| Gate | Required evidence or disposition | Current result |
|---|---|---|
| Owner storage/sharing policy | Raw episode video stays external; non-video artifacts are eligible for ordinary admission and sharing | Passed |
| Published source family | Chen et al. (2017), the public ContextLab release, and Zenodo v1.3 | Passed |
| Raw-video exclusion | Root `/tmp/` protection and an explicit no-Git/no-redistribution rule for actual episode video contents | Passed |
| Non-video artifact eligibility | Applicable provenance and story-text admission checks, without a new Sherlock-specific licence/consent/REB hold | Passed as policy; admission remains per artifact |
| Source identity | Edition-bound local media and stream identities with relation and alignment checks | Open |
| Annotation transformation | Reproducible workbook-to-TSV transformation | Open |
| Recall transformation | Reproducible public-release-to-local-CSV mapping | Open |
| Alias identity | Complete checked 17-entry source and omission mapping | Open |
| Story-text admission | Apply the ordinary checklist to each annotation or recall text artifact proposed for Git | Open per artifact |
| Coordinate semantics | Typed axes, declared transforms, missingness, and checked repair receipts | Open |
| Semantic authority | Predeclared, bounded use of coder proposals; no episode-truth promotion | Open |
| Scientific comparison | Frozen or cross-fitted design with coverage, refusal, and anti-circularity evidence | Open |

Open scientific gates delimit what each artifact and comparison may claim.
Passing them does not inherit admission across artifact classes, and no
non-video admission or scientific result ever admits the episode video files.

## Source-line court checklist

This record was checked against the current governing sources:

- Owner decision `post-01M1CN0CHHMYSA93R7AZMPZQB2` makes the storage and
  sharing disposition artifact-specific without amending
  [Constitution IX](../CONSTITUTION.md#ix-human-source-authority).
- The original experiment and 1,000 independently coded semantic segments are
  reported by [Chen et al. (2017)](https://doi.org/10.1038/nn.4450).
- The public [ContextLab release](https://github.com/ContextLab/sherlock-topic-model-paper/tree/81f90b8afa6dd714b780208bd89d1f1a26159ff5)
  states that it contains the raw annotations and recall transcripts analyzed
  in the Heusser et al. paper.
- The J. Chen Lab [Zenodo v1.3 release](https://doi.org/10.5281/zenodo.8208709)
  publishes word-timestamped Sherlock recall transcripts.
- Recall and transcript codec types still carry plain `StorySource`, so this
  court does not serialize participant content:
  [codec status](../../codec/README.md#current-status), line 31.
- Plain SHA-256 is integrity, not confidentiality or a safe receipt for
  non-public material:
  [codec security boundary](../../codec/README.md#security-boundary), lines 68–71.
- Non-public content identity must be keyed and must not fall back to plain
  digesting:
  [embedding contract](../adr/0001-embedding-contract-and-hard-gate.md#d6-sensitive-digests-and-receipts),
  lines 377–408, with the public summary in
  [embed-core](../../embed-core/README.md#what-is-implemented), lines 31–39.
- Film source identity, typed presentation axes, checked atlases, and timebase
  receipts are governed by
  [ADR 0007](../adr/0007-film-source-representation.md), especially lines
  129–207, 250–290, and 467–490, as amended by this owner-policy successor.
- The measured annotation structure, discontinuity, diagnostic-label status,
  source lineage, and minimum intake contract come from the
  [Sherlock source audit](sherlock-source-representation-audit.md), lines
  54–108, 127–202, and 204–275.
- Frozen, diagnostic, and cross-fitted regimes; fixture tiers; binding stages;
  and comparison gates come from the
  [movie narrative architecture plan](../plans/2026-08-29-movie-narrative-architecture.md),
  sections 8–12 at lines 453–833.

## Scope boundary

This successor amends the court, Sherlock source audit, ADR 0007, movie plan,
and the Sherlock B0 dependency sentence in the component ledger. It records
the artifact-specific owner policy but does not itself admit an annotation or
recall artifact, close B0 or P1, create a verifier or API, implement a source
adapter, inspect additional local content, accept terms, make a film-licence
claim, or adjudicate copyright.
