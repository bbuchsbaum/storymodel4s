package storymodel4s.laws

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.*
import org.typelevel.discipline.Laws
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.proposition.*
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
  private def parts(r: HsmmResult) =
    (r.posterior, r.flow, r.viterbi, r.logLikelihood, r.costs, r.admissibility, r.refinementPasses)

  def gateProof(using Arbitrary[AlignGens.Case], Arbitrary[AlignGens.FoilCase]): RuleSet =
    new DefaultRuleSet(
      "align.gateProof",
      None,
      "every inferred result re-validates to itself on its recall and view" -> forAll {
        (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val (p, f, v, ll, cs, a, n) = parts(r)
          HsmmResult.validated(c.recall, c.view, p, f, v, ll, cs, a, n) == Right(r)
      },
      "every forgery — mass, key, cost, path, flow, or a transplanted authentic record — is rejected" ->
        forAll { (f: AlignGens.FoilCase) =>
          val r = AlignGens.inferFoil(f)
          AlignGens.forgeries(r).forall { case (p, fl, v, cs, adm) =>
            HsmmResult
              .validated(
                f.recall,
                f.base.view,
                p,
                fl,
                v,
                r.logLikelihood,
                cs,
                adm,
                r.refinementPasses
              )
              .isLeft
          }
        },
      "a foil result has at least one inadmissible pair to forge onto" ->
        forAll { (f: AlignGens.FoilCase) => AlignGens.forgeries(AlignGens.inferFoil(f)).nonEmpty },
      "the gate, not the record, decides: re-validating against another recall fails when the gate disagrees" ->
        forAll { (f: AlignGens.FoilCase) =>
          // The foil's recall differs from the base recall only in the contradicting unit; its
          // result cannot be validated as if it belonged to a recall whose units the gate would
          // assess differently (unit ids differ, so the record's units are unknown there).
          val r = AlignGens.inferFoil(f)
          val (p, fl, v, ll, cs, a, n) = parts(r)
          HsmmResult.validated(f.base.recall, f.base.view, p, fl, v, ll, cs, a, n).isLeft ||
          f.base.recall.byId.contains(f.unit.id)
        },
      "dropping the admissibility record invalidates every anchored result" ->
        forAll { (c: AlignGens.Case) =>
          val r = AlignGens.infer(c)
          val anchored = r.posterior.rows.exists(_.sourceMass > 0.0)
          !anchored || HsmmResult
            .validated(
              c.recall,
              c.view,
              r.posterior,
              r.flow,
              r.viterbi,
              r.logLikelihood,
              r.costs,
              Map.empty,
              0
            )
            .isLeft
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
