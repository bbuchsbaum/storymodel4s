# M1 embeddings — discriminative spike spec (revision 2)

**Status:** Proposed (co-authored on mote topic `embeddings`; revision 2 applies
Codex's P0-5 and evaluation requirements). Companion to ADR 0001 rev 3.
**Purpose:** choose per-tier default providers/views and the structural
channel's role by evidence on our task, from fixtures frozen independently of
every tested channel — before any default is written into code.

## Question

Which (provider, view, channel union) maximizes **strict acceptable-target
recall at the declared level** for recall units against source nodes, with the
mode gate producing **zero false gating** and correct fidelity-facet detection,
at acceptable latency, memory, and privacy mode?

## Fixtures (independence first)

1. **Frozen selection set.** ≥ 8 short public-domain stories in ≥ 3 story
   families. For each: adjudicated source charts, hierarchy, segment summaries,
   acceptable target sets (with alternatives and levels), and external labels —
   produced and frozen **before and independently of** any tested embedding or
   structural channel (no channel under test participates in building them).
   Partitions by story family: development / calibration / untouched test.
2. **Natural human-recall panel.** A small set of genuine free recalls per
   development/calibration story, adjudicated for source anchor, fidelity
   facets, source-groundedness, external subtype, and thematic association.
   The untouched test partition's recalls are adjudicated but never inspected
   during development.
3. **Diagnostic (non-selection) material.** Metamorphic paraphrase sets (exact
   paraphrase, parent-summary, vague reference, entity swap, role-reversal,
   polarity, context, modality and outcome distortions, similar other event,
   association, intrusion), generated with a different provider than any under
   test. Used for coverage and sanity **only**; never for selection or
   calibration. WOG is a regression fixture.
4. **Interview repetition pairs** (exact repeat, paraphrase repeat, elaboration,
   correction) — adjudicated for the calibration partition.
5. Inputs are evaluated in two conditions: **oracle charts** (adjudicated) and
   **unattended raw text** (M1 acquisition once available).

## Systems under test

| Tier | Candidates |
|---|---|
| Portable free | hashed n-gram (fingerprinted function/seed/dimension), TF-IDF (fingerprinted corpus) |
| Structural (JVM) | grakern WL subtree / OA over reified charts (rounds 1–3), with explicit source/target incidence |
| Local JVM (ONNX) | bge-small/base-en-v1.5, e5-base-v2, nomic-embed-text-v1.5 (+ derived Matryoshka spaces), gte-base; late-pooled view where token embeddings are exposed |
| Local server | Ollama nomic-embed-text / mxbai-embed-large (if present on the bench host) |
| Remote (opt-in, non-sensitive fixtures only) | OpenAI text-embedding-3-small/large (+ derived truncations), Voyage voyage-3(-large), Cohere embed-v4, Gemini embedding, Jina v3 |

Views: Surface, Gloss, Contextual.template, Contextual.latepooled (distinct
space, full identity), Segment. Channel unions via RRF (rank only; nominations
preserved with channel, rank, raw score, space, receipt).

## Metrics (per hierarchy level, per item type, per story family with macro CIs)

Primary:
- **Strict acceptable-target recall@k** (k = 1, 3, 5, 10) at the declared
  level; **MRR**. Ancestor credit reported separately and secondary; the story
  root never satisfies a precise leaf.
- **Level confusion** matrix; **segment-summary accuracy**; **blend set
  coverage** (both intended anchors in top-k); **candidate burden** (mean
  candidate-set size).
- **Gate correctness**: zero false gating on faithful items; distortion items
  anchored with the correct `Distorted(facets)` — reported as source-anchor
  detection and conditional fidelity facets **separately**.
- **Open-world**: source-groundedness, external subtype, and optional thematic
  association reported separately; association/intrusion items → external mass
  > source mass after the rejection model.
- **Oracle vs predicted discourse labels** reported separately.
- **Sequential and three-clock panels**: discourse-order and world-order
  backward mass, compression, and dwell against adjudicated recall trajectories.

Secondary: foil indifference (dense views only), calibration (retrieval and
rejection as separate models; ECE equal-mass, Brier; only on adjudicated
targets ⇒ "calibrated", otherwise "benchmark-tuned"), latency per 1k requests,
peak memory, dimension, privacy mode, cost, replay stability (cosine ≥ 0.999
across runs).

## Pass / fail

- A tier default is chosen only on the untouched test partition: strict
  recall@5 ≥ 0.90 on exact paraphrases and ≥ 0.75 on vague references and
  parent summaries at their level, with story-macro 95% CIs excluding the next
  best system or a declared noninferiority margin (Δ ≤ 0.02).
- False gating = 0 on every system (otherwise the bug is in `align`; stop).
- Association/intrusion rule ≥ 0.9 after the rejection model on calibration,
  confirmed on test.
- The structural channel earns union membership if strict recall@5 on
  parent-summary or entity-swap items improves by ≥ 0.05 with a declared
  noninferiority margin (Δ ≤ 0.02) elsewhere; otherwise it stays an ablation.
- Late pooling is kept only if it beats `Contextual.template` on strict
  recall@5 by ≥ 0.03 for the same model.

## Outputs

`docs/reports/embed-bench-<date>.md` + JSON; a decision record appended to
ADR 0001 naming per-tier defaults; calibrated (adjudicated) or benchmark-tuned
(otherwise) `CostWeights`/rejection defaults for `align` — closing the
`TODO(M5)` items only in the calibrated case.

## Ownership

- claude-storymodel4s: `embed-core`, `embed-grakern` adapter, `embed-bench`,
  fixture-freezing tooling and adjudication protocol, `align` anchor/mode
  refactor, `interview` integration.
- codex-storymodel-release: adversarial review of ADR 0001 and this spec;
  `embed-transport` + codecs; keyed cache + `EncryptedStore`; `RemotePolicy`,
  `AuthorizedRemoteRequest`, `RemoteCapability`; receipt audit; bench report
  review; independence audit of the frozen fixtures.
- grakern upstream beads (grakern/.mote, tagged `storymodel4s`): protocol,
  directed WL, portable emitter, overlay hardening, edge policy, laws, 0.1.0-M1.
