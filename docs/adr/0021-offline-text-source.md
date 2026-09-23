# ADR 0021: deterministic offline text sources

Status: accepted for the bounded source-package implementation, 2026-09-23.
Owner: codex-temporal; Mote `bd-01M35PB1H5PDD55YVKR4TQ8M66`.

## Decision

`TextSourcePackage` is a checked, portable source for alignment without a narrative
model. It binds an existing `StorySource`, a checked `SurfaceAtlas`, and a named
`TextSegmentationProfile`. It is not a `StoryModel` and asserts no entities,
predicates, contexts, causal relations, salience or world chronology.

Two profiles have different, visible authority:

- `surface-semicolon/v1` runs the existing deterministic `SurfaceAnalyzer` for
  paragraphs, sentences and tokens. It adds heuristic clauses separated after
  semicolons within each sentence. Punctuation stays with the preceding clause;
  surrounding whitespace is excluded. These are surface fragments, not claims
  about grammatical clauses. A change to any segmentation rule needs a new
  profile version and regression evidence.
- `supplied-atlas/v1` accepts an existing checked atlas and preserves its units,
  spans, parent links and IDs. It claims no segmentation algorithm for those
  supplied boundaries. Missing granularities remain absent; the importer does
  not silently create or replace them.

`TextSourceSegment` exposes the non-token units with content-addressed target IDs.
IDs use the full SHA-256 of profile, canonical source checksum, kind and exact
span. Caller-selected story/unit IDs and ordinals do not define target identity.
Original surface unit IDs remain available separately for evidence joins. Package
serialization binds the entire atlas, including its parent links and ordering;
equal physical target IDs do not assert equal hierarchy or equal packages.

All spans are half-open UTF-16 code-unit offsets into `StorySource.canonicalText`.
The input's raw text and checksum are retained alongside canonical text and its
checksum. `story-source/v1` canonicalization names the existing normalization:
CRLF/CR become LF, trailing horizontal whitespace is removed, runs of more than
two newlines collapse, and leading/trailing blank lines are removed. Unicode
normalization is not performed; an input BOM is retained as content. Raw-file
byte offsets, Unicode code-point offsets and canonical UTF-16 offsets are not
interchangeable. This slice does not provide a raw-to-canonical offset crosswalk.

Construction rejects malformed UTF-8, unpaired UTF-16 surrogates, empty segment
inventories, empty spans, negative ordinals, and boundaries inside surrogate
pairs. It does not claim grapheme boundaries: a supplied atlas may deliberately
anchor a combining mark. Derived values use private non-case constructors;
decoding reconstructs them and refuses inconsistent derived fields.

## Consumers and exchange

`align.bridge.TextSourceView` selects paragraph, sentence or clause targets from
the same package. The selected units form a flat level-zero `SourceView` with
character support and ordinal discourse succession. It declares propositional
scope unavailable, missing salience and no world order. It also constructs the
existing checked `SourceRepresentation` using the canonical text checksum.
Selecting an absent granularity fails explicitly.

`text-source/v1` JSON carries source, atlas, profile, derived segments and explicit
capabilities. `segments.tsv` includes the canonical checksum, offset unit, kind,
original surface ID, target ID, ordinal, parent surface ID and exact text. A
manifest hashes the exact exported files. The independent Python reader checks
hashes, UTF-16 slicing and target identities rather than trusting producer labels.

The offline `textSourcePrepare` command accepts UTF-8 text and optionally its
`surface-atlas/v1` standoff file. It writes a new output directory and reports a
structured receipt. It never calls a provider or assigns encoding seconds.

The existing `StorySourceView` over a validated narrative model remains a separate
alternative. The package reports narrative-model and encoding-seconds capability
as unavailable. A downstream workflow can select a supplied validated model or
attach a checked presentation schedule; it must not reinterpret ordinal or
character coordinates as seconds. Wiring the mapping facade and broader prepare
command to this package remains separate acceptance work on their Mote issues.

## Rejected alternatives and verification

We reject running the recall segmenter's proposition heuristics on source text:
that would invent narrative assertions while purporting to perform segmentation.
We also reject presenting canonical offsets as raw-file offsets, stripping a BOM
without recording a transformation, and giving all imported atlases the generated
profile's provenance.

Tests cover deterministic identity, supplied-atlas preservation, Unicode/CRLF/BOM
coordinates, malformed input, missing capabilities and rejected construction
bypasses. Roundtrip/tamper checks, independent table reads, and killed validation
mutants qualify the boundary. A package-only candidate does not close downstream
facade/prepare integration or establish scientific mapping accuracy.
