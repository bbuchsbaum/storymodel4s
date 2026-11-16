# Film proposal inputs

`FilmProposalSurface` is the portable F1 boundary between an ordered inventory of caption or
annotation records and later film compilation. It does not acquire media, call a parser, choose a
claim status, or compile a story model.

Each `FilmSourceRecord` carries its exact description and checked physical support. Call
`FilmProposalSurface.derive` with the composed `SourceBundle`, original row/scene proposal units,
and a `FilmProposalDerivation`. The only accepted recipe is `FilmProposalSurface.RecipeV1`: it
joins records with one blank line and runs `SurfaceAnalyzer` over the resulting text. A description
that `StorySource` would canonicalize differently is refused rather than silently transformed.

The result retains original units with `surface = None`, creates one new proposal unit for each
generated sentence, and exposes the complete `FilmSentenceOrigin` table. Record order, descriptions,
receipt binding, output checksum, generated sentence structure, and origin table contribute to its
identity. Proposal text is useful for local charts but never replaces film physical support.

`FilmCompilerInput.of` then checks one chart association for every generated sentence and every
attempt's explicitly declared sentence scope. Chart provenance must agree exactly, including its
provider calls and full chart payload checksum. Attempts retain only `MediaTime` and `MediaPoint`
anchors; every anchor is rechecked against the complete bundle and must be contained in the exact
same-stream, same-axis union of its declared sentence envelopes. There is no hull, coordinate
conversion, or gap filling, and a direct primary-axis anchor remains required.

`provenanceFor` takes actual `ChartNodeRef`s. It returns the selected associations' provider calls,
deduplicating shared upstream calls; it does not infer parse membership from text offsets or media
ticks. This boundary confirms declared correspondence, not that an external parser was executed.

The independent expectation is selected before accepting submitted records. This executable
example is checked against `FilmInputSuite` (the `film-input-example` region). Import
`storymodel4s.core.*` and `storymodel4s.document.*` before using it:

```scala
def prepareFilmProposal(
    bundle: SourceBundle,
    expectedRecords: Vector[FilmSourceRecord],
    receivedRecords: Vector[FilmSourceRecord],
    originalUnits: Vector[NarrativeProposalUnit],
    expectedOutput: Checksum
): Either[DomainError, FilmProposalSurface] =
  FilmProposalSurface
    .expectedDerivation(bundle, expectedRecords, originalUnits, expectedOutput)
    .flatMap(expected =>
      FilmProposalSurface.derive(bundle, receivedRecords, originalUnits, expected)
    )
```

The caller must obtain `expectedRecords` and `expectedOutput` from its intended acquisition
inventory; deriving both sides from an untrusted submitted payload only declares that payload.
The test runs the accepting example and a swapped-input refusal. Original units preserve their
complete support and must have no surface sentence. `originalUnitIdentity` and
`expectedDerivation` expose the fixed identity recipe without requiring consumers to duplicate it.

Each `FilmParseBinding` names one `producingCall`, which must be an exact chart receipt for the
declared request, and its parser fingerprint must equal the chart's parser origin. Other receipts
remain as upstream provenance. The separate expected inventory pins `recipeIdentity`; this
portable join does not reconstruct a provider's request recipe or attest provider execution.
Selecting a different producing receipt changes the checked input identity.
