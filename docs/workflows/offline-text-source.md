# Prepare an offline text source

This workflow prepares exact surface targets for alignment without calling a
provider. It produces a checked source package, an analysis table and a manifest.
It does not build a narrative model or assign video/scanner times.

From a source checkout:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.textSourcePrepare /path/source.txt /path/new-output'
python3 tools/check_text_source_exchange.py /path/new-output
```

The output directory must not exist and its parent must exist. A successful
command returns exit 0 and a JSON receipt on stdout. Refusals return exit 2 and a
JSON error on stderr. An existing output is never overwritten. `manifest.json`
appears only after the payloads and completed manifest have been written; a
directory without it is incomplete. A failed write can leave that directory behind;
preserve it for diagnosis and retry with a new output path. Error receipts give
the failure class and summary, not every nested domain-error detail.

To preserve an existing standoff atlas:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.textSourcePrepare /path/source.txt /path/new-output --atlas /path/surface-atlas.json'
```

The atlas must use `surface-atlas/v1` and bind the canonical checksum of this
actual text. Caller-selected story and unit IDs are retained. Supplied paragraph,
sentence and clause boundaries are preserved; missing kinds remain absent.

## Outputs and coordinates

- `source.json` (`text-source/v1`) retains raw and canonical text, their separate
  SHA-256 identities, the atlas, segmentation profile, derived target IDs and
  explicit capabilities. Decoding reconstructs the checked package and rejects
  altered derived values.
- `segments.tsv` uses `quoted-tsv/v1`: every cell quoted, tab separators, LF record
  endings, UTF-8. Rows carry source checksum, offset unit, profile, target ID,
  original surface unit ID, qualified kind, original kind, ordinal, start,
  exclusive end, tagged parent and exact segment text. Each kind has its own
  ordinal axis; do not compare ordinal values across kinds as time or depth.
- `manifest.json` binds exact file hashes, byte lengths and column types.

Offsets are **half-open UTF-16 code units in canonical text**. Canonicalization
normalizes CRLF/CR to LF, removes trailing space/tab, collapses more than two
newlines and removes leading/trailing blank lines. The existing Java regex end
anchor also acts immediately before a final NEL (U+0085), line separator (U+2028)
or paragraph separator (U+2029): spaces/tabs before that character are trimmed on
each LF-separated line, and LF before it is trimmed at the end of the text.
Those separator characters themselves remain. It does not normalize Unicode
or strip a BOM. Plain UTF-8 readers retain the BOM; `utf-8-sig` and `UTF-8-BOM`
readers change the coordinate basis. Raw-file byte offsets, Python character
indices and these UTF-16 offsets can differ. There is no raw/canonical crosswalk
in this version.

For Python, use the independent checker above. To slice a canonical text using a
table row:

```python
piece = canonical_text.encode("utf-16-le")[2 * start : 2 * end].decode("utf-16-le")
```

For R, retain column text and tagged missingness when loading:

```r
segments <- read.delim("new-output/segments.tsv", quote = '"',
                       colClasses = "character", check.names = FALSE,
                       fileEncoding = "UTF-8")
```

The `text` column already contains the exact slice. R character indexing and
Python `len` are not substitutes for UTF-16 offsets when supplementary Unicode
characters occur. The checker verifies hashes, canonicalization, surrogate-safe
support, IDs, atlas joins and the complete TSV projection. It does not independently
re-run the Scala sentence segmentation algorithm.

## Segmentation and mapping

`surface-semicolon/v1` uses the existing deterministic surface analyzer and adds
semicolon fragments within sentences. A `surface-semicolon/v1:Clause` is an
explicit heuristic fragment, not a parsed grammatical clause. A sentence without
a semicolon has one such fragment. `supplied-atlas/v1` records supplied boundaries
without attributing them to an analyzer.

The generated profile retains nonlexical surface fragments: a second consecutive
semicolon, a BOM-only paragraph inside nonempty text, and NBSP/zero-width-space
fragments can be targets. Supplied punctuation, emoji and combining-mark anchors
are also valid. These targets count in the selected source-node inventory and
ordinal succession; their count is not a count of semantic or grammatical units.
Whole-input ASCII whitespace and BOM-only inputs are refused.

The library entry points are `TextSourcePackage.fromUtf8`, `.fromText`, `.analyze`
and `.fromAtlas`. The package exposes the existing checked `TextNarrativeAtlas`
and its `SourceBundle`. `TextSourceView.of(package, SurfaceUnitKind.Sentence)`
selects a present grain into a flat `SourceView` and checked `SourceRepresentation`.
Paragraph and clause selection work the same way; an absent kind is refused.
Propositions, salience and world chronology remain unavailable. The view's
discourse rank is the selected grain's dense order, distinct from a supplied
atlas's possibly sparse original unit ordinals.

A validated full narrative model uses the existing `StorySourceView` route.
Text-only intake reports narrative-model and encoding-seconds capabilities as
unavailable. Adding a presentation schedule or choosing a model must be an explicit
downstream operation. Ordinary-file mapping-facade and broader prepare-command
consumption remain open; this package is their checked input seam.

## Validation boundaries

The checked package and segment classes do not promise structural `equals` or
`hashCode`; compare their declared fields or canonical artifacts. Standalone JSON
decode compares parsed values and may admit semantically equal formatting (its
parser uses the last duplicate key). Exchange decode requires exact regenerated
file bytes. The Python reader separately refuses duplicate and undeclared fields.
The legacy `SourceView` requires a context value; text targets carry its
`NarratedWorld` placeholder with `Undeclared` propositional scope, which licenses
no narrative-context assertion.

Run the production-witness and independent-reader regression court with a new
output path (Python 3, standard library only):

```sh
sbt 'pipeline/Test/runMain storymodel4s.pipeline.TextSourceExchangeWitness /tmp/text-source-witnesses'
python3 tools/check_text_source_exchange.py --suite /tmp/text-source-witnesses
```

This checks generated BOM/Unicode inputs and a supplied atlas, then rehashes
undeclared capability fields to ensure rejection is semantic, not just a stale
file hash. The witness producer invokes the production CLI in-process; process
exit/channel behavior has a separate CLI court.

The design and rejected alternatives are recorded in
[ADR 0021](../adr/0021-offline-text-source.md).
