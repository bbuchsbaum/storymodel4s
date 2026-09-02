# Slice 1.5: close the front-half coverage gap

- **Date:** 2026-09-02
- **Mode:** single-developer (AGENTS.md).
- **Standard:** `vision.md`. Evidence points back to text; each phenomenon stays visible in the
  part of the model that can represent it faithfully; higher-order interpretation never erases
  the observation it was built from; alternatives remain inspectable.
- **Measured against:** the fifty captured War of the Ghosts recordings on `main`
  (`pipeline/src/test/resources/recordings/wog-captured`). Every change below is validated by
  replaying those, so the whole slice costs no API spend and no new text.

## The gap, measured

The first real pass proposed situations for 27 of 50 sentences. The 23 losses are not diffuse;
they are four mechanical classes, each with a named cause.

| Class | Sentences | Cause |
|---|---|---|
| A. Markers on non-concepts | 7 | The transport refuses a marker that sits on a role, a reentrancy, or a constant, so the whole sentence yields no chart |
| B. Coordination roots | 14 | The chart's focus is `and` with a predicate under each `:op`, and only a focus predicate may become a situation |
| C. Predicative roots | 1 | `(d / dead :domain (h / he))`: the root is a property, not a frame |
| D. Existential roots | 2 | `(p / person :quant many :location (e / egulac))`: the root is an entity with a locative |

Class A examples, verbatim from the captured replies: `:quant 1~e.0`, `:quant 5~e.2`,
`:polarity -~e.10`, `:poss h~e.4`.

## A. Represent non-concept markers instead of refusing them

**This reverses a decision I made in phase 1.4 and the data falsified.** The 1.4 brief told the
transport to refuse a marker that is not on a concept, on the grounds that the prompt forbids
them. Two things are now clear. The model emits them because they carry real information: the
token a quantity came from, the token at which an entity is mentioned again, the token that
carried a negation. And `amr-interop` already represents exactly those three cases
(`AmrCandidates.markerAlignment`): a role marker becomes a `RelationAlignment`, a target marker
on a node becomes a `ReentrancyAlignment`, and a target marker on a literal becomes an
attribute `RelationAlignment`.

So the refusal throws away evidence the model volunteered and the chart can hold. Under
`vision.md` that is the wrong trade: a reentrancy marker is mention-level textual evidence, and
mention-level evidence is the thing the surface axis exists to keep.

The transport derives sidecar rows for every marker in decoder order, whatever its site, and
names the site in the row's provider node id so the row remains inspectable. A marker whose
edge the decoder cannot canonicalize is still a typed refusal: unrepresentable is not the same
as unwelcome. `MarkerNotOnConcept` stops being a failure and becomes a recorded site kind.

Expected: 7 sentences gain charts, and every chart gains alignment evidence it previously
dropped.

## B. One situation per coordinated predicate

`(a / and :op1 (l / land-01 ...) :op2 (g / go-02 ...))` is two events in one sentence. Today the
provider abstains because the focus is not a predicate, and the compiler would refuse anyway:
a situation's source must be the chart focus (`compiler.scala:1293`).

The rule: when the focus is a coordination concept (`and`, `or`, `multi-sentence`), each `:opN`
child that is an admissible predicate root becomes its own situation, in `:op` order. Coordinated
siblings are recorded as such, so nothing pretends they were separate sentences. They are ordered
in discourse by their `:op` index, and their story-world relation stays `Unclear`, because `and`
asserts conjunction and not sequence: inventing `Before` here would be exactly the fabricated
epistemic license the design contract forbids.

The compiler must therefore accept a non-focus source when the focus is a coordinator and the
source is one of its `:op` children. That widening is narrow and stated as a rule, not a
loosening: no other non-focus source becomes admissible.

Expected: 14 sentences gain situations, most of them two apiece.

## C and D. Predicative and existential roots

A property root with `:domain` (`dead :domain he`) is a State whose predicate is the property and
whose `:domain` filler is its participant. An entity root with a locative or existential
quantifier (`person :quant many :location egulac`) is a State of existence at a place. Both are
admitted as States under closed, named rules in `RulesText`, both keep the abstention path for any
other non-predicate root, and neither invents a frame that the chart does not carry.

Expected: 3 sentences.

## What is not in this slice

No prompt change. The prompt is not the defect in class A, and classes B, C and D are ours.
Re-prompting to avoid coordination would suppress a true reading of the sentence to fit our
compiler, which is backwards.

## Acceptance

Replaying the fifty captured recordings, with no new spend:

- every sentence is accounted for in the coverage ledger, as now;
- charts rise from 43 toward 50 and proposed situations from 27 toward the high forties;
- every remaining abstention names a class we have decided not to admit, not a class we failed
  to notice;
- the counts are pinned as literals in the pipeline court, so a regression moves a number;
- each new admission rule carries a mutation proof that deleting it turns a named test red.
