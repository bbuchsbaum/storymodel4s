package storymodel4s.laws

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.*
import org.typelevel.discipline.Laws
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.proposition.*
import storymodel4s.recall.{DiscourseFunction, RecallUnit, RecallUnitId}
import storymodel4s.story.*

/** Discipline rule sets for the propositional chart contract. */
object ChartLaws extends Laws:
  def chart(using Arbitrary[PropositionChart[Checked]]): RuleSet =
    new DefaultRuleSet(
      "proposition.chart",
      None,
      "validation is idempotent" -> forAll { (c: PropositionChart[Checked]) =>
        ChartValidator.validate(c.unchecked).toOption.map(_.unchecked) == Some(c.unchecked)
      },
      "canonical form is idempotent" -> forAll { (c: PropositionChart[Checked]) =>
        val once = Canonical.form(c)
        Canonical.form(once).unchecked == once.unchecked
      },
      "isomorphism is reflexive" -> forAll { (c: PropositionChart[Checked]) =>
        ChartIsomorphism.isomorphic(c, c)
      },
      "isomorphism is symmetric" -> forAll {
        (a: PropositionChart[Checked], b: PropositionChart[Checked]) =>
          ChartIsomorphism.isomorphic(a, b) == ChartIsomorphism.isomorphic(b, a)
      },
      "isomorphic charts share a checksum" -> forAll {
        (a: PropositionChart[Checked], b: PropositionChart[Checked]) =>
          !ChartIsomorphism.isomorphic(a, b) || Canonical.checksum(a) == Canonical.checksum(b)
      },
      "self-compatibility has no gates" -> forAll { (c: PropositionChart[Checked]) =>
        val r = ChartCompatibility.compare(c, c)
        !r.gated && !r.roleReversal && !r.polarityConflict
      }
    )

/** Discipline rule sets for the story validator: every generated shape is valid, and every
  * minimally-invalid mutant is rejected under exactly its injected law.
  */
object StoryLaws extends Laws:
  def validator(using Arbitrary[StorySmall.Built], Arbitrary[Mutant]): RuleSet =
    new DefaultRuleSet(
      "story.validator",
      None,
      "every generated shape validates clean" -> forAll { (b: StorySmall.Built) =>
        val report = StoryValidator.validate(b.draft(), ValidationPolicy.strict).report
        Prop(report.isClean) :| report.render
      },
      "every generated shape promotes to Validated" -> forAll { (b: StorySmall.Built) =>
        StoryValidator.validate(b.draft(), ValidationPolicy.strict).validated.nonEmpty
      },
      "a mutant reports its injected law" -> forAll { (m: Mutant) =>
        val report = StoryValidator.validate(m.draft, ValidationPolicy.strict).report
        Prop(report.byLaw.contains(m.law)) :| s"${m.law} not in ${report.render}"
      },
      "an error mutant is not promoted" -> forAll { (m: Mutant) =>
        val outcome = StoryValidator.validate(m.draft, ValidationPolicy.default)
        val isWarningOnly = outcome.report.errors.isEmpty
        Prop(isWarningOnly == outcome.validated.nonEmpty)
      }
    )

/** Discipline rule sets for story-world temporal relations. Generated models always carry at least
  * two situations, so the edge laws are never vacuous.
  */
object TemporalLaws extends Laws:
  def temporal(using Arbitrary[TemporalRelation], Arbitrary[StorySmall.Built]): RuleSet =
    new DefaultRuleSet(
      "story.temporal",
      None,
      "generated models have at least two situations" -> forAll { (b: StorySmall.Built) =>
        b.situations.size >= 2
      },
      "converse is an involution" -> forAll { (r: TemporalRelation) => r.converse.converse == r },
      "edge inverse is an involution" -> forAll { (r: TemporalRelation, b: StorySmall.Built) =>
        val e = TemporalEdge(
          b.situations(0),
          r,
          b.situations(1),
          b.world,
          StorySmall.meta("law", EpistemicStatus.Hypothesized, None)
        )
        e.inverse.inverse == e
      },
      "canonicalization is idempotent and preserves the relation up to converse" -> forAll {
        (r: TemporalRelation, b: StorySmall.Built) =>
          val e = TemporalEdge(
            b.situations(0),
            r,
            b.situations(1),
            b.world,
            StorySmall.meta("law", EpistemicStatus.Hypothesized, None)
          )
          val c = e.canonical
          c.relation.isCanonical && c.canonical == c &&
          (c == e || c == e.inverse)
      },
      "the validator rejects stored non-canonical relations" -> forAll { (b: StorySmall.Built) =>
        val bad = TemporalEdge(
          b.situations(0),
          TemporalRelation.After,
          b.situations(1),
          b.world,
          StorySmall.meta("law", EpistemicStatus.Hypothesized, None)
        )
        val g = b.graph.copy(relations =
          b.graph.relations.copy(temporal = b.graph.relations.temporal :+ bad)
        )
        val outcome = StoryValidator.validate(b.draft(graph = g), ValidationPolicy.default)
        outcome.report.violations.exists(_.law == "temporal.canonical-relation")
      }
    )

/** Discipline rule sets for alignment posteriors and flows. */
object AlignmentLaws extends Laws:
  private val eps = 1e-9

  private def agree(a: Map[?, Double], b: Map[?, Double]): Boolean =
    a.forall((s, m) => math.abs(b.asInstanceOf[Map[Any, Double]].getOrElse(s, 0.0) - m) < 1e-6)

  def alignment(using Arbitrary[AlignGens.Case]): RuleSet =
    new DefaultRuleSet(
      "align.hsmm",
      None,
      "posterior rows are distributions" -> forAll { (c: AlignGens.Case) =>
        val p = AlignGens.infer(c).posterior
        p.rows.forall(r => r.mass.values.forall(_ >= -eps) && math.abs(r.total - 1.0) < 1e-6)
      },
      "flow marginals match the posterior in both directions" -> forAll { (c: AlignGens.Case) =>
        val res = AlignGens.infer(c)
        res.flow.steps.zipWithIndex.forall { (step, i) =>
          val from = step.fromMarginal
          val to = step.toMarginal
          val row = res.posterior.rows(i).mass
          val next = res.posterior.rows(i + 1).mass
          agree(row, from) && agree(from, row) && agree(next, to) && agree(to, next)
        }
      },
      "flow has one step fewer than the recall has units" -> forAll { (c: AlignGens.Case) =>
        val res = AlignGens.infer(c)
        res.flow.steps.size == math.max(0, res.posterior.rows.size - 1)
      },
      "absent evidence terms are inert: without charts, d_chart and d_wl are Missing and a structural provider changes nothing (ADR 0001 rev 3 §D4b)" ->
        forAll { (c: AlignGens.Case) =>
          val base = AlignGens.infer(c)
          val withProvider = AlignGens.inferWith(
            c,
            DefaultLocalCostModel(
              semantic = c.semantic,
              structural = StructuralDistance.of((_, _) => 0.0)
            )
          )
          val samePosterior = base.posterior.rows.zip(withProvider.posterior.rows).forall {
            (a, b) => agree(a.mass, b.mass) && agree(b.mass, a.mass)
          }
          val recorded = withProvider.costs.values.forall(_.values.forall { b =>
            b.exclusion.nonEmpty || b.mode.isEmpty ||
            (b.missingTerms == Set(CostTerm.Chart, CostTerm.Structural) &&
              !b.has(CostTerm.Chart) && !b.has(CostTerm.Structural))
          })
          val sameTotals = base.costs.forall { (u, m) =>
            m.forall { (s, b) => withProvider.costs(u).get(s).exists(_.total == b.total) }
          }
          samePosterior && recorded && sameTotals
        },
      "localizability lies in [0, 1] and is defined iff the unit has source mass" -> forAll {
        (c: AlignGens.Case) =>
          val k = c.view.sourceNodeCount
          AlignGens
            .infer(c)
            .posterior
            .rows
            .forall { r =>
              r.localizability(k) match
                case Some(l) => r.sourceMass > 0.0 && l >= -eps && l <= 1 + eps
                case None    => r.sourceMass <= 0.0
            }
      }
    )

/** The mode-gate laws of ADR 0001 rev 3 §D5 (L1–L3), checked on adversarial foils: a unit built to
  * contradict a leaf on one facet, with cosine 1 on that leaf and random weights, temperature, and
  * refinement passes.
  */
object ModeGateLaws extends Laws:
  private def run(f: AlignGens.FoilCase): HsmmResult =
    GraphHsmm
      .infer(f.recall, f.base.view, f.candidates, f.costModel, f.config)
      .fold(e => throw new IllegalStateException(e.message), identity)

  def modeGate(using Arbitrary[AlignGens.FoilCase]): RuleSet =
    new DefaultRuleSet(
      "align.modeGate",
      None,
      "L1: the faithful mode of a contradicted anchor never carries mass, for any distance, weights, temperature, or refinement" ->
        forAll { (f: AlignGens.FoilCase) =>
          val res = run(f)
          val row = res.posterior.rows.head
          val adm = res.admissibility(f.unit.id).get(f.target)
          adm.exists(a => !a.faithful && a.contradictions.contains(f.contradiction)) &&
          row.faithfulMassOn(f.target) == 0.0 &&
          !res.costs(f.unit.id).contains(AlignState.Source(f.target)) &&
          !res.viterbi.contains(AlignState.Source(f.target))
        },
      "L1': the contradicted anchor stays recallable in its distorted mode (no omission + intrusion)" ->
        forAll { (f: AlignGens.FoilCase) =>
          val res = run(f)
          val row = res.posterior.rows.head
          val facet = f.contradiction.facet
          row.distortedMassOn(f.target) > 0.0 &&
          res.costs(f.unit.id).keys.exists {
            case AlignState.Distorted(r, fs) => r == f.target && fs.contains(facet)
            case _                           => false
          }
        },
      "L2: candidate order and fused rank never change the posterior" ->
        forAll { (f: AlignGens.FoilCase) =>
          val base = f.candidates
          val set = base.set(f.unit.id)
          val reversed =
            Candidates(Map(f.unit.id -> set.copy(nominations = set.nominations.reverse)))
          val fused =
            Candidates(
              Map(f.unit.id -> CandidateSet.fuse(Vector(set, set.without(Channels.lexical))))
            )
          val a = run(f)
          val b = GraphHsmm
            .infer(f.recall, f.base.view, reversed, f.costModel, f.config)
            .fold(e => throw new IllegalStateException(e.message), identity)
          val c = GraphHsmm
            .infer(f.recall, f.base.view, fused, f.costModel, f.config)
            .fold(e => throw new IllegalStateException(e.message), identity)
          a.posterior == b.posterior && a.posterior == c.posterior
        },
      "L3: the cost model is consulted only for admissible (anchor, mode) pairs, each once per pass set" ->
        forAll { (f: AlignGens.FoilCase) =>
          val spy = new AlignGens.SpyCostModel(f.costModel)
          val res = GraphHsmm
            .infer(f.recall, f.base.view, f.candidates, spy, f.config)
            .fold(e => throw new IllegalStateException(e.message), identity)
          val adm = res.admissibility(f.unit.id)
          val expected =
            adm.toVector.flatMap((ref, a) => a.modes.map(m => (f.unit.id, ref, m))).toSet
          spy.calls.toSet == expected && spy.calls.size == expected.size &&
          !spy.calls.contains((f.unit.id, f.target, FidelityMode.Faithful))
        }
    )

/** The gated result is a proof (forward-review P0; ADR 0001 rev 3 L1): only [[GraphHsmm.infer]] or
  * [[HsmmResult.validated]] can produce an [[HsmmResult]], and `validated` refuses any part that
  * puts mass, a cost, or a path step on an `(anchor, mode)` pair the gate did not admit.
  */
object GateProofLaws extends Laws:
  private def parts(r: HsmmResult): AlignGens.Forgery =
    (r.candidateAnchors, r.posterior, r.flow, r.viterbi, r.costs)

  def gateProof(using Arbitrary[AlignGens.Case], Arbitrary[AlignGens.FoilCase]): RuleSet =
    new DefaultRuleSet(
      "align.gateProof",
      None,
      "every inferred result re-validates to itself on its recall and view, with and without its echo" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val (a, p, f, v, cs) = parts(r)
          AlignGens.revalidate(c.recall, c.view, r, parts(r)) == Right(r) &&
          HsmmResult.validated(
            c.recall,
            c.view,
            a,
            p,
            f,
            v,
            r.logLikelihood,
            cs,
            r.refinementPasses,
            Some(r.admissibilityEcho)
          ) == Right(r)
        },
      "the derived fields name the recall and the view: fingerprints match and matched accepts" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          r.viewFingerprint == c.view.contentFingerprint &&
          r.recallChecksum == AlignWire.recallChecksum(c.recall) &&
          r.candidateAnchors.keySet == c.recall.units.map(_.id).toSet &&
          r.admissibility.forall((u, m) => m.keySet == r.candidateAnchors(u).toSet) &&
          AlignWire.matched(r, r.viewFingerprint, r.recallChecksum) == Right(r)
        },
      "every forgery of a foil result — mass, key, cost, path, flow, cross-record — is rejected (the un-nominated class is covered by the Case-based law: a foil nominates every node)" ->
        forAll { (f: AlignGens.FoilCase) =>
          val r = AlignGens.inferFoil(f)
          AlignGens
            .forgeries(r, f.base.view)
            .forall(fg => AlignGens.revalidate(f.recall, f.base.view, r, fg).isLeft)
        },
      "the un-nominated forgery is refused by nomination, not by silence" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val rows = r.posterior.rows
          rows.zipWithIndex.forall { (row, i) =>
            val nominated = r.candidateAnchors(row.unit).toSet
            c.view.nodes.map(_.ref).filterNot(nominated.contains).sorted.forall { ref =>
              val bad = AlignState.Source(ref)
              val forged = AlignmentMatrix
                .of(
                  rows
                    .updated(i, AlignmentRow.of(row.unit, row.mass.updated(bad, 0.0)).toOption.get)
                )
                .toOption
                .get
              AlignGens.revalidate(
                c.recall,
                c.view,
                r,
                (r.candidateAnchors, forged, r.flow, r.viterbi, r.costs)
              ) match
                case Left(AlignError.GateViolation(u, s, d)) =>
                  u == row.unit && s == bad && d.contains("not nominated")
                case _ => false
            }
          }
        },
      "a foil result has at least one inadmissible pair to forge onto" ->
        forAll { (f: AlignGens.FoilCase) =>
          AlignGens.forgeries(AlignGens.inferFoil(f), f.base.view).nonEmpty
        },
      "the gate, not the record, decides: re-validating against another recall fails when the gate disagrees" ->
        forAll { (f: AlignGens.FoilCase) =>
          // The foil's recall differs from the base recall only in the contradicting unit; its
          // result cannot be validated as if it belonged to a recall whose units the gate would
          // assess differently (unit ids differ, so the record's units are unknown there).
          val r = AlignGens.inferFoil(f)
          AlignGens.revalidate(f.base.recall, f.base.view, r, parts(r)).isLeft ||
          f.base.recall.byId.contains(f.unit.id)
        },
      "an echo that is not the gate's digest is refused as drift, before any key is gated" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val stale = AdmissibilityEcho.of(Map.empty)
          stale == r.admissibilityEcho || {
            val (a, p, f, v, cs) = parts(r)
            HsmmResult.validated(
              c.recall,
              c.view,
              a,
              p,
              f,
              v,
              r.logLikelihood,
              cs,
              r.refinementPasses,
              Some(stale)
            ) == Left(AlignError.GateDrift(stale, r.admissibilityEcho))
          }
        },
      "dropping every nomination invalidates every anchored result" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val anchored = r.posterior.rows.exists(_.sourceMass > 0.0)
          val none = r.candidateAnchors.map((u, _) => u -> Vector.empty[SourceNodeRef])
          !anchored || AlignGens
            .revalidate(c.recall, c.view, r, (none, r.posterior, r.flow, r.viterbi, r.costs))
            .isLeft
        }
    )

/** The wire laws (bead `HsmmResult wire`): fingerprints are content addresses — invariant under
  * iteration order, sensitive to every field the aligner reads — and the validating record
  * factories are the identity on records a real inference produced.
  */
object WireLaws extends Laws:
  def wire(using Arbitrary[AlignGens.Case]): RuleSet =
    new DefaultRuleSet(
      "align.wire",
      None,
      "the view fingerprint is invariant under node, edge, and world-order iteration order" ->
        forAll { (c: AlignGens.Case) =>
          val (same, _) = AlignGens.fingerprintVariants(c.view)
          same.forall(_.contentFingerprint == c.view.contentFingerprint)
        },
      "the view fingerprint changes under any node-summary field, edge, world-order, or length change" ->
        forAll { (c: AlignGens.Case) =>
          val (_, different) = AlignGens.fingerprintVariants(c.view)
          val base = c.view.contentFingerprint
          different.forall((_, v) => v.contentFingerprint != base) &&
          different.map(_._2.contentFingerprint).distinct.size == different.size
        },
      "the recall checksum is invariant under unit storage order and changes with a span or id" ->
        forAll { (c: AlignGens.Case) =>
          val base = AlignWire.recallChecksum(c.recall)
          val u0 = c.recall.ordered.head
          val shifted = u0.copy(span = SpanSet.one(TextSpan.unsafe(0, 1)))
          val renamed = u0.copy(id = RecallUnitId.unsafe("zzz-renamed"))
          def swap(u: RecallUnit) =
            c.recall.copy(units = c.recall.units.map(x => if x.id == u0.id then u else x))
          AlignWire.recallChecksum(c.recall.copy(units = c.recall.units.reverse)) == base &&
          AlignWire.recallChecksum(swap(shifted)) != base &&
          AlignWire.recallChecksum(swap(renamed)) != base
        },
      "a unit-text-only change flips the recall checksum (same boundaries, different content)" ->
        forAll { (c: AlignGens.Case) =>
          val base = AlignWire.recallChecksum(c.recall)
          val u0 = c.recall.ordered.head
          def swap(u: RecallUnit) =
            c.recall.copy(units = c.recall.units.map(x => if x.id == u0.id then u else x))
          AlignWire.recallChecksum(swap(u0.copy(text = u0.text + "!"))) != base &&
          AlignWire.recallChecksum(
            swap(u0.copy(proposition = u0.proposition.copy(lemmas = u0.proposition.lemmas + "zz")))
          ) != base &&
          AlignWire.recallChecksum(
            swap(
              u0.copy(function =
                if u0.function == DiscourseFunction.TaskCommentary then
                  DiscourseFunction.Association
                else DiscourseFunction.TaskCommentary
              )
            )
          ) != base
        },
      "a receipt-inconsistent optional term is rejected by the factory" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          r.costs.values.flatMap(_.values).filter(_.mode.nonEmpty).forall { b =>
            // the generated cases carry no charts: every optional receipt reduces to nothing, so a
            // present optional term, or an unrecorded missing one, contradicts its receipt
            val present = AlignWire.costBreakdown(
              b.terms.updated(CostTerm.Chart, 0.25),
              b.mode,
              b.exclusion,
              b.total,
              b.missingTerms - CostTerm.Chart,
              b.sourceChartCoverage,
              b.reductions
            )
            val unrecorded = AlignWire.costBreakdown(
              b.terms,
              b.mode,
              b.exclusion,
              b.total,
              b.missingTerms - CostTerm.Structural,
              b.sourceChartCoverage,
              b.reductions
            )
            present.isLeft && unrecorded.isLeft
          }
        },
      "every cost breakdown and reduction receipt of a real result rebuilds to itself" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          r.costs.values.flatMap(_.values).forall { b =>
            AlignWire.costBreakdown(
              b.terms,
              b.mode,
              b.exclusion,
              b.total,
              b.missingTerms,
              b.sourceChartCoverage,
              b.reductions
            ) == Right(b) &&
            b.reductions.values.forall { rc =>
              AlignWire.reductionReceipt(
                rc.reducer,
                rc.members,
                rc.excludedMembers,
                rc.sourceChartCoverage,
                rc.observedEstimateCoverage
              ) == Right(rc)
            }
          }
        }
    )

/** Discipline rule sets for the population same-view law (design record §11): an aggregate pools
  * only proofs gated against its own view, and each proof only with the recall it was made from.
  */
object PopulationLaws extends Laws:
  def population(using Arbitrary[AlignGens.Case]): RuleSet =
    new DefaultRuleSet(
      "align.population",
      None,
      "an aggregate accepts a proof of its own view and its receipt is bound to that view" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val subject = SubjectAlignment(SubjectId.unsafe("s0"), c.recall, r, None)
          PopulationAggregate.of(c.view, Vector(subject)).exists { p =>
            p.receipt.viewFingerprint == c.view.contentFingerprint &&
            p.receipt.subjectCount == 1 &&
            p.receipt.recallChecksums == Vector(AlignWire.recallChecksum(c.recall))
          }
        },
      "an aggregate refuses a proof from any view with different content, even with the same node ids" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val subject = SubjectAlignment(SubjectId.unsafe("s0"), c.recall, r, None)
          val (same, different) = AlignGens.fingerprintVariants(c.view)
          def viewMismatch(e: Either[AlignError, PopulationAggregate]) = e.left.exists {
            case AlignError.FingerprintMismatch(f, _, _) => f.startsWith("viewFingerprint")
            case _                                       => false
          }
          same.forall(v => PopulationAggregate.of(v, Vector(subject)).isRight) &&
          different.forall((_, v) => viewMismatch(PopulationAggregate.of(v, Vector(subject))))
        },
      "a population succeeds iff every subject's proof is of the aggregate's view" ->
        forAll { (c: AlignGens.Case) =>
          val own = AlignGens.infer(c)
          val (_, different) = AlignGens.fingerprintVariants(c.view)
          val lemmaView = different.collectFirst { case ("lemmas", v) => v }.get
          val foreign = AlignGens.infer(c.copy(view = lemmaView))
          val a = SubjectAlignment(SubjectId.unsafe("a"), c.recall, own, None)
          val b = SubjectAlignment(SubjectId.unsafe("b"), c.recall, foreign, None)
          val mixed = PopulationAggregate.of(c.view, Vector(a, b))
          val mixedNamesB = mixed.left.exists {
            case AlignError.FingerprintMismatch(f, _, _) => f.contains("(subject b)")
            case _                                       => false
          }
          PopulationAggregate.of(c.view, Vector(a)).isRight &&
          PopulationAggregate.of(lemmaView, Vector(b)).isRight &&
          mixedNamesB &&
          PopulationAggregate.of(lemmaView, Vector(a, b)).isLeft
        },
      "an aggregate refuses a subject whose recall is not the recall of its proof" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val u0 = c.recall.ordered.head
          val other = c.recall.copy(units =
            c.recall.units.map(x => if x.id == u0.id then u0.copy(text = u0.text + "!") else x)
          )
          val subject = SubjectAlignment(SubjectId.unsafe("s0"), other, r, None)
          PopulationAggregate.of(c.view, Vector(subject)).left.exists {
            case AlignError.FingerprintMismatch(f, _, _) => f.startsWith("recallChecksum")
            case _                                       => false
          }
        },
      "the receipt is invariant under subject order" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val subjects = Vector("b", "a", "c").map(id =>
            SubjectAlignment(SubjectId.unsafe(id), c.recall, r, None)
          )
          val x = PopulationAggregate.of(c.view, subjects)
          val y = PopulationAggregate.of(c.view, subjects.reverse)
          x.isRight && x.map(_.receipt) == y.map(_.receipt) && x == y
        }
    )

/** Discipline rule sets for feature estimates and reducers. */
object EstimateLaws extends Laws:
  private val reducers: Gen[ScalarReducer] = Gen.oneOf(
    ScalarReducer.Sum,
    ScalarReducer.Mean,
    ScalarReducer.Maximum,
    ScalarReducer.Variance,
    ScalarReducer.Slope
  )

  private val missingReasons: Gen[MissingReason] = Gen.oneOf(
    MissingReason.NotInLexicon,
    MissingReason.OutOfVocabulary,
    MissingReason.ProviderAbstained,
    MissingReason.Excluded,
    MissingReason.Undefined(UndefinedReason.NotFinite)
  )

  private val missing: Gen[Sample[Double]] =
    for
      i <- Gen.chooseNum(0, 100)
      r <- missingReasons
    yield Sample(i, Estimate.missing(r), 1.0)

  private val observed: Gen[Sample[Double]] =
    for
      i <- Gen.chooseNum(0, 100)
      v <- Gen.chooseNum(-10.0, 10.0)
    yield Sample(i, Estimate.observed(v), 1.0)

  def estimates: RuleSet =
    new DefaultRuleSet(
      "features.estimate",
      None,
      "reducers over all-missing samples yield Missing(AllMissing), never zero" -> forAll(
        reducers,
        Gen.nonEmptyListOf(missing)
      ) { (r, ss) =>
        WindowReducer.scalar(r).reduce(NonEmptyVector.fromVectorUnsafe(ss.toVector)) match
          case Estimate.Missing(MissingReason.AllMissing) => true
          case _                                          => false
      },
      "slope over a single position is Undefined, not zero" -> forAll(observed) { s =>
        WindowReducer.scalar(ScalarReducer.Slope).reduce(NonEmptyVector.one(s)) match
          case Estimate.Missing(MissingReason.Undefined(UndefinedReason.SlopeNeedsTwoPositions)) =>
            true
          case _ => false
      },
      "non-finite observations never become Observed" -> forAll(
        Gen.oneOf(Double.NaN, Double.PositiveInfinity)
      ) { v =>
        !Estimate.score(v).isObserved
      },
      "mean ≤ maximum on observed samples" -> forAll(Gen.nonEmptyListOf(observed)) { ss =>
        val nev = NonEmptyVector.fromVectorUnsafe(ss.toVector)
        (
          WindowReducer.scalar(ScalarReducer.Mean).reduce(nev),
          WindowReducer.scalar(ScalarReducer.Maximum).reduce(nev)
        ) match
          case (Estimate.Observed(m, _), Estimate.Observed(x, _)) => m <= x + 1e-9
          case _                                                  => false
      },
      "coverage counts observed ≤ eligible and rejects the reverse" -> forAll(
        Gen.listOf(Gen.oneOf(missing, observed))
      ) { ss =>
        val n = ss.size
        val k = ss.count(_.estimate.isObserved)
        Coverage.of(n, k).exists(c => c.fraction >= 0.0 && c.fraction <= 1.0) &&
        Coverage.of(k, n + 1).isLeft
      }
    )
