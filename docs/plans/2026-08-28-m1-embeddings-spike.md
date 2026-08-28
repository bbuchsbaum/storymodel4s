# M1 embeddings — discriminative spike spec

**Status:** Proposed (co-authored on mote topic `embeddings`).
**Purpose:** decide the default embedding provider and view per tier, and the
structural channel's role, by evidence on our task — before any default is
written into code. Companion to ADR 0001.

## Question

Which (provider, view, channel-union) maximizes **candidate recall** for
recall units against source nodes across hierarchy levels, while the
structural gates keep **false acceptance** at zero, at acceptable latency,
memory, and privacy mode?

## Corpus (built first; no gold required)

1. **Metamorphic paraphrase corpus** (§88.2), multi-story: ≥ 8 short public-
   domain stories (folktales/fables), each with a hand-checked narrative
   fixture *or* a machine-built silver model once M1 acquisition exists.
   Generated with a provider/prompt package **different** from any provider
   under test; a stratified 10% sample is human-audited and the audit outcome
   is recorded.
   Item types per source node: exact paraphrase; parent-summary (targets the
   scene/episode); vague reference; entity swap; role-reversal foil;
   polarity foil; context foil (reported → asserted); semantically similar
   other event (hard negative); association; intrusion (null).
2. **WOG** is a regression fixture only, never used for model selection.
3. **Interview repetition pairs** from the birthday fixture plus generated
   pairs (exact repeat, paraphrase repeat, elaboration, correction).
4. All items carry intended target(s), hierarchy level, and item type;
   splits are **leave-story-out**.

## Systems under test

| Tier | Candidates |
|---|---|
| Portable free | hashed n-gram (char 3–5 + word uni/bi), TF-IDF over lemmas |
| Structural (JVM) | grakern WL subtree / OA over reified charts (rounds 1–3) |
| Local JVM (ONNX) | bge-small-en-v1.5, bge-base-en-v1.5, e5-base-v2, nomic-embed-text-v1.5 (+ Matryoshka 256/512), gte-base; late-pooled Contextual view where token embeddings are exposed |
| Local server | Ollama nomic-embed-text / mxbai-embed-large (if available on the bench host) |
| Remote (opt-in, non-sensitive corpus only) | OpenAI text-embedding-3-small/large (256/1024/3072), Voyage voyage-3(-large), Cohere embed-v4, Gemini embedding, Jina v3 |

Views per system: Surface, Gloss, Contextual (template), Contextual
(late-pooled, where possible), Segment. Channel unions via RRF (rank only).

## Metrics (all reported per hierarchy level and per item type)

- **Candidate recall@k** (k = 1, 3, 5, 10) of the intended target *or its
  ancestor at the intended level*.
- **Foil indifference** (dense semantic views only): cosine(foil, target) −
  cosine(paraphrase, target); near zero is expected and acceptable.
- **Post-retrieval structural-gate rejection**: fraction of role/polarity/
  context foils that are excluded by `ContradictionDetector` after retrieval
  (must be 1.0 — law L1).
- **Open-world**: association and intrusion items → external mass > source
  mass after `GraphHsmm.infer` with calibrated floor.
- **Calibration**: retrieval P(target ∈ top-k) and rejection (external floor)
  fitted leave-story-out; ECE (equal-mass bins) and Brier per model.
- **Cost**: latency per 1k requests, peak memory, dimension, privacy mode,
  price per 1M tokens (remote).
- **Stability**: exact-replay from receipts; vector cosine agreement ≥ 0.999
  across two runs for local models.

## Pass / fail

- A tier default is chosen only if recall@5 ≥ 0.90 on exact paraphrases and
  ≥ 0.75 on vague references and parent summaries at their intended level,
  leave-story-out.
- Structural-gate rejection of role/polarity foils = 1.0 on every system
  (otherwise the bug is in `align`, not the embedder — stop and fix).
- Association/intrusion external-mass rule ≥ 0.9 after calibration.
- The structural channel earns its place if adding it to the union raises
  recall@5 on parent summaries or entity-swap items by ≥ 0.05 with no loss
  elsewhere; otherwise it remains an ablation baseline.
- Late pooling is kept only if it beats template Contextual on recall@5 by
  ≥ 0.03 for the same model.

## Outputs

`docs/reports/embed-bench-<date>.md` (auto-generated) + JSON; a decision
record appended to ADR 0001 naming the per-tier defaults, and calibrated
`CostWeights`/`externalFloor` defaults for `align` — closing the `TODO(M5)`
items in the WOG suite.

## Ownership

- claude-storymodel4s: `embed-core`, `embed-structural` adapter, `embed-bench`,
  corpus generation tooling, `align`/`interview` integration, WOG TODO closure.
- codex-storymodel-release: adversarial review of ADR 0001; `embed-transport`
  + codecs spike; keyed cache + `EncryptedStore`; `PrivacyPolicy` and
  `RemoteCapability`; receipt audit; bench report review.
- grakern upstream beads (filed in grakern's tracker): directed graphs,
  portable emitter, sparse rows, self-loop/parallel policy, laws, 0.1.0-M1.
