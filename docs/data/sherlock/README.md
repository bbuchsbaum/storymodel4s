# Sherlock canonical video-recall source set

This directory is the content-free admission record for the project's canonical
Sherlock video-recall source set. It binds the two local media parts, the released
1,000-row annotation workbook and its local TSV representation, and 17 released
word-timestamped recall sources and their local CSV representations.

"Canonical" here means **the exact reference artifacts against which the film
workflow is developed and validated**. It does not mean that the local media bytes
have been proven to be the exact presentation edition used in the experiment, that
the annotations are accepted narrative semantics, or that a recall-tuned event
model is independent gold.

## Why this was not admitted before

The obstacle was scientific identity, not a new consent or licence court. The files
had no checked record tying local bytes to public artifacts, the annotation seconds
reset between runs, two scan-break rows have no TR coordinates, 16 convenience
aliases silently omit one of the 17 recall sources, and three different segmentation
populations could all have been called simply "events." Without these records, a
green analysis could still have used the wrong source, clock, alias, or target.

The owner decision in Mote post `post-01M1CR50ZYM6Q8MG03CBTH9JVRP` is binding for
this intake: only actual Sherlock video bytes are excluded from storage and sharing.
Non-video artifacts remain eligible under the ordinary artifact-specific workflow.
This slice does not propose any text-bearing source artifact for Git admission and
contains no participant recall prose or episode annotation prose.

## Records

| Record | Contract |
|---|---|
| `source-manifest.json` | Two external media byte identities, elementary-stream identities, container metadata, ordered local-pair claim, and explicitly unconfirmed experimental-edition identity |
| `annotation-lineage.json` | Public workbook identity, exact workbook-to-TSV replay, missingness, and distinct segmentation populations |
| `recall-lineage.json` | Exact 17-source public release, local CSV identities, content-free reconciliation receipts, and per-source story-text disposition |
| `alias-map.json` | Total one-to-one mapping of 16 convenience aliases, including the omitted fifth source and shifted numbering |
| `timebase-repair.json` | Separate raw, scanner, media, recall, and repaired clocks plus the published two-run repair replay |

The JSON files are data, not prose conventions. Gates parse every file, canonicalize
it with `jq -S -c`, and publish SHA-256 digests. Referenced local hashes are checked
without printing content.

## Scientific use

- `Microsegment1000` is the released fine annotation population. Rows 481 and 482
  are retained in its source identity but typed as scan-break records; the published
  repaired analysis population contains 998 rows.
- `Scene50` is a coarser human-coded ordering scaffold. Its boundaries are not exact
  fine-segment containment evidence.
- `TopicHmmEvent30` is a recall-tuned diagnostic population. It must never be used
  as independent gold or as both a feature-selection target and an evaluation target.
- The two media files stay outside Git. Their hashes and stream metadata are the
  shareable identities used by future adapters.
- The 17 text-bearing recall exports stay digest-bound outside Git in this slice.
  A later proposal to store their contents must run the ordinary story-text checklist
  per artifact; no Sherlock-wide consent, REB, BBC, or aggregate-only condition is
  introduced here.

## Workflow handoff

This B0 record enables, but does not itself implement:

1. portable film source and time-axis contracts;
2. a read-only Sherlock annotation adapter with replay receipts; and
3. an end-to-end recall-alignment command and inspectable validation report.

Those stages must consume the identities and coordinate semantics here. They may not
infer source edition, repair clocks from filenames, collapse aliases, or promote the
30-event diagnostic into accepted narrative structure.

Primary public sources are the
[Chen lab timestamped-transcript release](https://github.com/jchenlab-jhu/Word-timestamped-transcripts),
its [Zenodo release](https://doi.org/10.5281/zenodo.8208709), and the
[Heusser et al. analysis repository](https://github.com/ContextLab/sherlock-topic-model-paper).
