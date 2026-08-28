# Mission

The mission of storymodel4s is to build an **unattended and auditable** system
for representing stories, recall, and the transformation between them. The
current library provides portable Scala 3 types, laws, validators, and inference
algorithms for those artifacts. Automatic construction of a complete story
model from raw text is the M1–M5 programme and is not yet implemented.

## What we are building

The target system follows one compositional pipeline:

```text
text or transcript
  -> exact surface atlas and word traversal
  -> local proposition charts and aligned feature tracks
  -> document identity, reference, and context
  -> events, states, relations, scenes, episodes, and trajectories
  -> partial open-world recall alignment and multidimensional outputs
```

Each layer has a narrow responsibility.

- The surface atlas preserves canonical text, stable offsets, tokens, clauses,
  sentences, turns, and exact span lookup.
- Feature tracks attach scalar or vector measurements to that shared axis.
  Declared window plans can compute sums, means, slopes, kernels, or other
  reductions while retaining coverage, missingness, and derivation receipts.
- Proposition charts represent local concepts, roles, polarity, reentrancy,
  embedded content, partial values, and exact source alignment. AMR/PENMAN is a
  standards-compatible adapter into this contract, not the global story
  ontology and not a prerequisite for usable output.
- Document semantics preserves mentions so that a later resolver can establish
  cross-sentence entity and event identity, reference, speech, belief,
  intention, and other contexts without destroying local evidence.
- The story model represents typed temporal, causal, participant, state-change,
  and hierarchy relations. It keeps discourse time separate from story-world
  time and retains both resolved structure and boundary evidence.
- The recall model represents a second process with its own order, discourse
  functions, uncertainty, and external material.
- Alignment is partial, probabilistic, hierarchical, directed, and open-world.
  It permits omission, compression, elaboration, blending, backtracking,
  misordering, association, and intrusion.

## How unattended, auditable builds work

Parsers, language models, embedding models, rules, and future providers all
implement replaceable acquisition interfaces. They return typed proposals with
source evidence and receipts. They do not write accepted scientific claims
directly.

Deterministic resolvers check proposal schemas, endpoint types, graph laws,
temporal consistency, evidence spans, and configured acceptance policies. A
build may accept a claim, retain weighted alternatives, leave it unresolved,
or reject it. Every outcome is a valid machine-readable result. Human expertise
is used to design standards, gold data, prompts, calibration, and audits; it is
not required to process an ordinary input.

Every accepted explicit claim must cite exact UTF-16 code-unit spans without
cutting a Unicode code point. Inferences must cite upstream claims. Every
numeric credence must be identified as a raw score or as a probability
calibrated by a named model. Provider calls, configurations, artifacts, and
cache keys must be receipted so a build can be replayed from its recorded
inputs.

## Operating commitments

We will:

1. preserve exact evidence and make every accepted claim traceable to the input
   or to named upstream claims;
2. support direct traversal from words to windows to narrative units and back
   again;
3. use similarity to retrieve candidates and typed structure to adjudicate role
   direction, polarity, embedded propositions, context, and chronology;
4. keep local propositions, continuous features, document identity, narrative
   relations, and hierarchy distinct but interoperable;
5. treat discourse time, story-world time, and recall time as separate clocks,
   and keep reported, believed, intended, and hypothetical content in explicit
   scopes;
6. represent omission, gist, blending, elaboration, backtracking, association,
   intrusion, ambiguity, missingness, and provider disagreement as explicit
   outcomes rather than error residue; an unresolved build must return
   alternatives or `Unresolved`, never manufacture precision merely to finish;
7. keep truth, salience, phenomenology, accessibility, and veridicality as
   separate quantities;
8. make every build versioned, content-addressed, cache-replayable, and graph
   diffable;
9. use typed, sparse, provider-neutral contracts that run on the JVM, Scala.js,
   and Scala Native where portable; portable modules must not depend on JVM-only
   services or runtime-specific parsing features such as regex lookaround;
10. remain sparse by construction, with no dense event-pair or unit-to-node
    allocation in core processing;
11. record every feature space used to infer a boundary or hierarchy and never
    test that feature against the induced structure without an ablation,
    cross-fitting, or an independent boundary set;
12. validate with structural laws and adversarial foils, and establish a
    leave-story-out harness before any learned component ships;
13. expose multidimensional recall signatures first and derive scalar or legacy
    scores only through named, versioned policies;
14. protect sensitive autobiographical material through explicit provider,
    provenance, and relational pseudonymization boundaries.

## Boundaries

The mission is not to produce a single canonical embedding, a bag of generic
triples, or an opaque accuracy score. It is not to turn every plausible causal
interpretation into source fact. It is not to require PropBank coverage, a
particular AMR parser, an LLM provider, a graph database, or a human annotator
during a production build. AMR is an interoperability adapter, not the story
ontology; parser output is never ground truth; and automatic output is never
called gold merely because it passed validation.

For natural autobiographical recall, the system is designed to characterize the
episode implied by the interview, its specificity, organization, and expressed
phenomenology. It cannot establish historical truth or genuine re-experiencing
without independent evidence. Current target-episode induction is rule-based
and uncalibrated. For known or staged source events, those extra questions can
eventually be measured through explicit source-to-recall alignment.

The current repository is the portable foundation for this mission. It does not
yet include provider adapters, the unattended build orchestrator, automatic
cross-sentence identity resolution, relation extraction, hierarchy induction,
or the corpora needed for scientific calibration. Their absence must be stated
plainly until the corresponding gates pass. The first release is
English-oriented and does not train a parser or foundation model.
