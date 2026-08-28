package storymodel4s.align

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import storymodel4s.features.Estimate
import storymodel4s.recall.RecallUnitId

class PopulationSuite extends ScalaCheckSuite:
  import AnnaFixture.{view, e1, e5, sc1, sc2, root}

  private val eps = 1e-9

  private def infer(
      recall: storymodel4s.recall.RecallGraph,
      cands: Candidates,
      model: LocalCostModel
  ): HsmmResult =
    GraphHsmm.infer(recall, view, cands, model).fold(e => fail(e.message), identity)

  // ---- three real subjects: full recall, summary-only recall, unrankable recall --------------

  private lazy val full: HsmmResult =
    infer(AnnaFixture.recall, AnnaFixture.candidates, AnnaFixture.costModel)
  private lazy val summary: HsmmResult =
    val f = AnnaFixture.summary
    infer(f.recall, f.candidates, f.costModel)
  private lazy val unranked: HsmmResult =
    val cands = CandidateGenerator(SemanticDistance.abstaining, lexicalOverlap = false)
      .generate(AnnaFixture.recall.ordered, view)
    infer(AnnaFixture.recall, cands, DefaultLocalCostModel(semantic = SemanticDistance.abstaining))

  private def sid(s: String): SubjectId = SubjectId.unsafe(s)

  private lazy val real: PopulationAggregate =
    PopulationAggregate
      .of(
        view,
        Vector(
          SubjectAlignment(sid("s-full"), full, Some(40)),
          SubjectAlignment(sid("s-summary"), summary, Some(8)),
          SubjectAlignment(sid("s-unranked"), unranked, None)
        )
      )
      .fold(e => fail(e.message), identity)

  test("real subjects: grounded subjects exclude the unrankable one") {
    assertEquals(real.groundedSubjects, Vector(sid("s-full"), sid("s-summary")))
    val ext = real.externalMass(sid("s-unranked")).get
    assert(ext(ExternalState.Unranked) > 0.0)
    assertEquals(real.sourceMass(sid("s-unranked")), Some(0.0))
  }

  test("real subjects: visitation follows the posteriors") {
    val y1 = real.visitation(e1)
    val ySc1 = real.visitation(sc1)
    // u0 is a coarse anchor: its mass splits between e1 and the enclosing scene sc1.
    assert(
      y1(sid("s-full")) + ySc1(sid("s-full")) > 0.5,
      s"full recall should visit e1 or sc1: $y1 / $ySc1"
    )
    assert(y1(sid("s-full")) > 0.0)
    assertEquals(y1(sid("s-unranked")), 0.0)
    val ySummary = real.visitation(sc2)(sid("s-summary")) + real.visitation(root)(sid("s-summary"))
    assert(ySummary > 0.0, "summary subject should place mass on a segment")
    assert(real.expectedVisits(e5) <= 3.0 + eps)
    val vr = real.visitationRate(e1)
    assertEquals(vr.coverage.eligible, 3)
    assertEquals(vr.coverage.observed, 2)
    assert(vr.rate.isObserved)
  }

  test("real subjects: population flow, hubs, backward mass, surviving relations") {
    assert(real.totalFlow > 0.0)
    val hubs = real.hubs(3)
    assert(hubs.nonEmpty)
    assert(hubs.map(_._2).zip(hubs.map(_._2).drop(1)).forall((a, b) => a >= b))
    val bw = real.backwardFlowMass
    bw.discourse match
      case Estimate.Observed(v, _) => assert(v >= 0.0 && v <= 1.0 + eps)
      case other => fail(s"expected an observed discourse backward mass, got $other")
    val causal = real.survivingRelations(RelationLayer.Causal, 0.0)
    assertEquals(causal.surviving.size, causal.total)
    val strict = real.survivingRelations(RelationLayer.Causal, 1.01)
    assertEquals(strict.surviving.size, 0)
  }

  test("real subjects: visitation matrix is sparse and consistent with visitation") {
    val m = real.visitationMatrix
    assertEquals(m.rowIds, real.subjectIds.map(_.value))
    assertEquals(m.colIds, real.nodeRefs.map(_.key))
    val expected = real.subjectIds.zipWithIndex.flatMap { case (s, i) =>
      real.nodeRefs.zipWithIndex.flatMap { case (v, j) =>
        val y = real.visitation(v)(s)
        if y > 0.0 then Some((i, j) -> y) else None
      }
    }.toMap
    assertEquals(m.entries, expected)
    assert(m.nnz > 0)
    assertEquals(m.sorted.map(_._1), m.sorted.map(_._1).sorted)
  }

  test("determinism: the same inputs give identical aggregates") {
    val again = PopulationAggregate
      .of(view, real.subjects)
      .fold(e => fail(e.message), identity)
    assertEquals(again.visitationMatrix, real.visitationMatrix)
    assertEquals(again.populationFlow, real.populationFlow)
    assertEquals(again.hubs(5), real.hubs(5))
  }

  // ---- constructor checks --------------------------------------------------------------------

  test("of rejects empty populations, duplicate ids, unknown nodes, malformed rows") {
    assert(PopulationAggregate.of(view, Vector.empty).isLeft)
    val a = SubjectAlignment(sid("s"), full, None)
    assert(PopulationAggregate.of(view, Vector(a, a.copy(wordCount = Some(1)))).isLeft)
    val alien = SourceNodeRef.Situation(storymodel4s.core.SituationId.unsafe("not-in-view"))
    val x0 = RecallUnitId.unsafe("x0")
    val badRow = AlignmentRow(x0, Map(AlignState.Source(alien) -> 1.0))
    // A gated result whose admissibility admits the alien anchor: the proof holds, but the
    // population rejects it because the anchor is absent from the view.
    val badResult = HsmmResult
      .validated(
        AlignmentMatrix(Vector(badRow)),
        TransitionFlow(Vector.empty),
        Vector(AlignState.Source(alien)),
        0.0,
        Map.empty,
        Map(x0 -> Map(alien -> Admissibility.faithfulOnly)),
        0
      )
      .fold(e => fail(e.message), identity)
    assert(PopulationAggregate.of(view, Vector(SubjectAlignment(sid("t"), badResult, None))).isLeft)
    // A NaN row cannot even become a gated result.
    val x1 = RecallUnitId.unsafe("x1")
    val nanRow = AlignmentRow(x1, Map(AlignState.Source(e1) -> Double.NaN))
    val nanResult = HsmmResult.validated(
      AlignmentMatrix(Vector(nanRow)),
      TransitionFlow(Vector.empty),
      Vector(AlignState.Source(e1)),
      0.0,
      Map.empty,
      Map(x1 -> Map(e1 -> Admissibility.faithfulOnly)),
      0
    )
    assert(nanResult.isLeft)
  }

  // ---- synthetic generators for properties ---------------------------------------------------

  private val sourceStates: Vector[AlignState] =
    view.nodes.map(n => AlignState.Source(n.ref)).sortBy(_.key)

  private def genRow(unit: Int, allowExternal: Boolean): Gen[AlignmentRow] =
    val states = if allowExternal then sourceStates ++ AlignState.externals else sourceStates
    for
      raw <- Gen.listOfN(states.size, Gen.choose(0.0, 1.0))
      zeroed <- Gen.listOfN(states.size, Gen.prob(0.4))
    yield
      val masses = raw.zip(zeroed).map((m, z) => if z then 0.0 else m)
      val total = masses.sum
      val norm = if total <= 0.0 then masses.updated(0, 1.0) else masses.map(_ / total)
      AlignmentRow(RecallUnitId.unsafe(s"g$unit"), states.zip(norm).filter(_._2 > 0.0).toMap)

  /** Independent coupling `F_i = P_i ⊗ P_{i+1}`: marginals equal the rows exactly. */
  private def outerFlow(rows: Vector[AlignmentRow]): TransitionFlow =
    TransitionFlow(rows.zip(rows.drop(1)).map { (a, b) =>
      val mass = for
        (s, ma) <- a.mass.toVector
        (t, mb) <- b.mass.toVector
      yield (s, t) -> ma * mb
      FlowStep(a.unit, b.unit, mass.toMap)
    })

  private def genResult(allowExternal: Boolean): Gen[HsmmResult] =
    for
      n <- Gen.choose(1, 5)
      rows <- Gen.sequence[Vector[AlignmentRow], AlignmentRow](
        (0 until n).map(genRow(_, allowExternal))
      )
    yield
      // Synthetic rows are all faithful anchors or externals: record every anchor as admitted.
      val adm = rows.map { r =>
        r.unit -> r.mass.keys.toVector.flatMap(_.anchor).map(_ -> Admissibility.faithfulOnly).toMap
      }.toMap
      HsmmResult
        .validated(
          AlignmentMatrix(rows),
          outerFlow(rows),
          rows.flatMap(_.argmax),
          0.0,
          Map.empty,
          adm,
          0
        )
        .fold(e => throw new AssertionError(e.message), identity)

  private def genPopulation(allowExternal: Boolean): Gen[PopulationAggregate] =
    for
      k <- Gen.choose(1, 4)
      results <- Gen.listOfN(k, genResult(allowExternal))
    yield PopulationAggregate
      .of(
        view,
        results.zipWithIndex.map((r, i) => SubjectAlignment(sid(s"p$i"), r, None)).toVector
      )
      .fold(e => throw new AssertionError(e.message), identity)

  given Arbitrary[PopulationAggregate] = Arbitrary(genPopulation(allowExternal = true))

  property("Y_sv lies in [0, 1] and is zero iff the subject placed no mass on v") {
    forAll { (p: PopulationAggregate) =>
      p.nodeRefs.forall { v =>
        p.visitation(v).forall { case (s, y) =>
          val m = p.subjects.find(_.subject == s).get.result.posterior.columnMass.getOrElse(v, 0.0)
          y >= 0.0 && y <= 1.0 && ((y == 0.0) == (m <= 0.0))
        }
      }
    }
  }

  property("expected visits never exceed the number of subjects") {
    forAll { (p: PopulationAggregate) =>
      p.nodeRefs.forall(v => p.expectedVisits(v) <= p.subjectIds.size + eps)
    }
  }

  property("population flow row sums are bounded by summed non-final posteriors") {
    forAll { (p: PopulationAggregate) =>
      p.nodeRefs.forall { v =>
        val out = p.populationFlow.collect { case ((a, _), m) if a == v => m }.sum
        val bound = p.subjects.map { s =>
          s.result.posterior.rows.dropRight(1).map(_.sourceMassOn(v)).sum
        }.sum
        out <= bound + eps
      }
    }
  }

  property("without external mass, flow row sums equal summed non-final posteriors") {
    forAll(genPopulation(allowExternal = false)) { p =>
      p.nodeRefs.forall { v =>
        val out = p.populationFlow.collect { case ((a, _), m) if a == v => m }.sum
        val exact = p.subjects.map { s =>
          s.result.posterior.rows.dropRight(1).map(_.sourceMassOn(v)).sum
        }.sum
        math.abs(out - exact) <= 1e-9
      }
    }
  }

  property("an all-external subject changes only external masses and coverage") {
    forAll { (p: PopulationAggregate) =>
      val extRow = AlignmentRow(
        RecallUnitId.unsafe("ext0"),
        Map(AlignState.unranked -> 1.0)
      )
      val extResult =
        HsmmResult
          .validated(
            AlignmentMatrix(Vector(extRow)),
            TransitionFlow(Vector.empty),
            Vector(AlignState.unranked),
            0.0,
            Map.empty,
            Map.empty,
            0
          )
          .fold(e => throw new AssertionError(e.message), identity)
      val q = PopulationAggregate
        .of(view, p.subjects :+ SubjectAlignment(sid("zz-ext"), extResult, None))
        .fold(e => throw new AssertionError(e.message), identity)
      val nodesSame = p.nodeRefs.forall { v =>
        q.columnMass(v) == p.columnMass(v) && q.expectedVisits(v) == p.expectedVisits(v) &&
        q.visitationRate(v).rate == p.visitationRate(v).rate
      }
      nodesSame && q.populationFlow == p.populationFlow && q.hubs(3) == p.hubs(3) &&
      q.groundedSubjects == p.groundedSubjects &&
      q.visitationRate(p.nodeRefs.head).coverage.eligible ==
        p.visitationRate(p.nodeRefs.head).coverage.eligible + 1 &&
        q.externalMass(sid("zz-ext")).exists(_(ExternalState.Unranked) == 1.0)
    }
  }

  property("surviving relations are monotone decreasing in the threshold") {
    forAll(genPopulation(allowExternal = true), Gen.choose(0.0, 1.0), Gen.choose(0.0, 1.0)) {
      (p, t1, t2) =>
        val (lo, hi) = if t1 <= t2 then (t1, t2) else (t2, t1)
        RelationLayer.values.forall { layer =>
          val a = p.survivingRelations(layer, lo).surviving.toSet
          val b = p.survivingRelations(layer, hi).surviving.toSet
          b.subsetOf(a)
        }
    }
  }
