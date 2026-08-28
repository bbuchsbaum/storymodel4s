package storymodel4s.laws

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.*
import org.typelevel.discipline.Laws
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

/** Discipline rule sets for story-world temporal relations. */
object TemporalLaws extends Laws:
  def temporal(using Arbitrary[TemporalRelation], Arbitrary[StorySmall.Built]): RuleSet =
    new DefaultRuleSet(
      "story.temporal",
      None,
      "converse is an involution" -> forAll { (r: TemporalRelation) => r.converse.converse == r },
      "edge inverse is an involution" -> forAll { (r: TemporalRelation, b: StorySmall.Built) =>
        b.situations.size < 2 || {
          val e = TemporalEdge(
            b.situations(0),
            r,
            b.situations(1),
            b.world,
            StorySmall.meta("law", EpistemicStatus.SurfaceExplicit, None)
          )
          e.inverse.inverse == e
        }
      },
      "canonical edges stay canonical; non-canonical ones canonicalize by inversion" -> forAll {
        (r: TemporalRelation, b: StorySmall.Built) =>
          b.situations.size < 2 || {
            val e = TemporalEdge(
              b.situations(0),
              r,
              b.situations(1),
              b.world,
              StorySmall.meta("law", EpistemicStatus.SurfaceExplicit, None)
            )
            e.canonical.relation.isCanonical
          }
      },
      "the validator rejects stored non-canonical relations" -> forAll { (b: StorySmall.Built) =>
        b.situations.size < 2 || {
          val bad = TemporalEdge(
            b.situations(0),
            TemporalRelation.After,
            b.situations(1),
            b.world,
            StorySmall.meta(
              "law",
              EpistemicStatus.SurfaceExplicit,
              Some(b.graph.situations(b.situations(0)).support)
            )
          )
          val g = b.graph.copy(relations =
            b.graph.relations.copy(temporal = b.graph.relations.temporal :+ bad)
          )
          val outcome = StoryValidator.validate(b.draft(graph = g), ValidationPolicy.default)
          outcome.report.violations.exists(_.law == "temporal.canonical-relation")
        }
      }
    )

/** Discipline rule sets for alignment posteriors and flows. */
object AlignmentLaws extends Laws:
  private val eps = 1e-9

  def alignment(using Arbitrary[AlignGens.Case]): RuleSet =
    new DefaultRuleSet(
      "align.hsmm",
      None,
      "posterior rows are distributions" -> forAll { (c: AlignGens.Case) =>
        val p = AlignGens.infer(c).posterior
        p.rows.forall(r => r.mass.values.forall(_ >= -eps) && math.abs(r.total - 1.0) < 1e-6)
      },
      "flow marginals match the posterior" -> forAll { (c: AlignGens.Case) =>
        val res = AlignGens.infer(c)
        res.flow.steps.zipWithIndex.forall { (step, i) =>
          val from = step.fromMarginal
          val to = step.toMarginal
          res.posterior
            .rows(i)
            .mass
            .forall((s, m) => math.abs(from.getOrElse(s, 0.0) - m) < 1e-6) &&
          res.posterior.rows(i + 1).mass.forall((s, m) => math.abs(to.getOrElse(s, 0.0) - m) < 1e-6)
        }
      },
      "localizability lies in [0, 1]" -> forAll { (c: AlignGens.Case) =>
        AlignGens
          .infer(c)
          .posterior
          .rows
          .forall(r => r.localizability >= -eps && r.localizability <= 1 + eps)
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

  private val missing: Gen[Sample[Double]] =
    Gen.chooseNum(0, 100).map(i => Sample(i, Estimate.missing(MissingReason.NotInLexicon), 1.0))

  private val observed: Gen[Sample[Double]] =
    for
      i <- Gen.chooseNum(0, 100)
      v <- Gen.chooseNum(-10.0, 10.0)
    yield Sample(i, Estimate.observed(v), 1.0)

  def estimates: RuleSet =
    new DefaultRuleSet(
      "features.estimate",
      None,
      "reducers over all-missing samples yield Missing, never zero" -> forAll(
        reducers,
        Gen.nonEmptyListOf(missing)
      ) { (r, ss) =>
        !WindowReducer.scalar(r).reduce(NonEmptyVector.fromVectorUnsafe(ss.toVector)).isObserved
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
      "coverage counts observed ≤ eligible" -> forAll(Gen.listOf(Gen.oneOf(missing, observed))) {
        ss =>
          val cov = Coverage.unsafe(ss.size, ss.count(_.estimate.isObserved))
          cov.observed <= cov.eligible && cov.fraction >= 0.0 && cov.fraction <= 1.0
      }
    )
