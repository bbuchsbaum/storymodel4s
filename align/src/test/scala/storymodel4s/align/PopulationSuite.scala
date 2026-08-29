package storymodel4s.align

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import storymodel4s.core.{StorySource, SurfaceAnalyzer}
import storymodel4s.features.Estimate
import storymodel4s.recall.{RecallGraph, RecallRelations}

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
          SubjectAlignment(sid("s-full"), AnnaFixture.recall, full, Some(40)),
          SubjectAlignment(sid("s-summary"), AnnaFixture.summary.recall, summary, Some(8)),
          SubjectAlignment(sid("s-unranked"), AnnaFixture.recall, unranked, None)
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
    assertEquals(
      PopulationAggregate.of(view, Vector.empty),
      Left(AlignError.SizeMismatch("population has no subjects"))
    )
    val a = SubjectAlignment(sid("s"), AnnaFixture.recall, full, None)
    assert(PopulationAggregate.of(view, Vector(a, a.copy(wordCount = Some(1)))).isLeft)
    val alien = SourceNodeRef.Situation(storymodel4s.core.SituationId.unsafe("not-in-view"))
    // A result nominating a node absent from the view cannot even become a gated result: the
    // proof derives every admissibility entry from the mode gate on this view over the nominated
    // anchors, and a nominated anchor must be a node of the view.
    val u0 = full.posterior.rows.head.unit
    assert(full.admissibility(u0).contains(e1))
    val alienAnchors =
      full.candidateAnchors.updated(u0, (full.candidateAnchors(u0) :+ alien).sorted)
    val alienResult = HsmmResult.validated(
      AnnaFixture.recall,
      view,
      alienAnchors,
      full.posterior,
      full.flow,
      full.viterbi,
      full.logLikelihood,
      full.costs,
      full.refinementPasses
    )
    assert(alienResult.isLeft)
    // A NaN row cannot become a gated result either.
    val r0 = full.posterior.rows.head
    val nanRows =
      full.posterior.rows.updated(0, AlignmentRow(r0.unit, r0.mass.map((k, _) => k -> Double.NaN)))
    val nanResult = HsmmResult.validated(
      AnnaFixture.recall,
      view,
      full.candidateAnchors,
      AlignmentMatrix(nanRows),
      full.flow,
      full.viterbi,
      full.logLikelihood,
      full.costs,
      full.refinementPasses
    )
    assert(nanResult.isLeft)
  }

  // ---- same-view law: a population is bound to one view and to each subject's recall ---------

  /** The same node ids as [[AnnaFixture.view]] with one lemma added to `e1`: a view a reader would
    * confuse with the original, and exactly what the fingerprint must distinguish.
    */
  private lazy val foilView: InMemorySourceView =
    InMemorySourceView(
      view.nodes.map(n => if n.ref == e1 then n.copy(lemmas = n.lemmas + "zzz") else n),
      view.edges,
      view.worldOrder,
      view.textLength
    )

  private lazy val fullOnFoil: HsmmResult =
    GraphHsmm
      .infer(AnnaFixture.recall, foilView, AnnaFixture.candidates, AnnaFixture.costModel)
      .fold(e => fail(e.message), identity)

  test("same-view law: a proof gated against a different view is refused, naming the subject") {
    assertNotEquals(foilView.contentFingerprint, view.contentFingerprint)
    assertEquals(fullOnFoil.viewFingerprint, foilView.contentFingerprint)
    val foreign = SubjectAlignment(sid("s-foreign"), AnnaFixture.recall, fullOnFoil, None)
    val own = SubjectAlignment(sid("s-own"), AnnaFixture.recall, full, None)
    val mixed = PopulationAggregate.of(view, Vector(own, foreign))
    assert(mixed.isLeft, "a mixed-view population must be refused regardless of error shape")
    mixed match
      case Left(AlignError.PopulationViewMismatch(subject, proof, aggregate)) =>
        assertEquals(subject, sid("s-foreign"))
        assertEquals(proof, foilView.contentFingerprint)
        assertEquals(aggregate, view.contentFingerprint)
      case other => fail(s"expected a typed population-view mismatch, got $other")
    // the wrong way round is refused too: the original proof does not fit the foil view
    PopulationAggregate.of(foilView, Vector(own)) match
      case Left(AlignError.PopulationViewMismatch(subject, proof, aggregate)) =>
        assertEquals(subject, sid("s-own"))
        assertEquals(proof, view.contentFingerprint)
        assertEquals(aggregate, foilView.contentFingerprint)
      case other => fail(s"expected a typed population-view mismatch, got $other")
    // and the foil proof aggregates on the foil view, receipt bound to it
    val onFoil =
      PopulationAggregate.of(foilView, Vector(foreign)).fold(e => fail(e.message), identity)
    assertEquals(onFoil.receipt.viewFingerprint, foilView.contentFingerprint)
  }

  test("same-view law: the view check precedes the node check") {
    // every anchor of the foreign proof exists in both views, so without the fingerprint check
    // the proof would pass the unknown-node scan; the fingerprint is what refuses it
    val foreign = SubjectAlignment(sid("s-foreign"), AnnaFixture.recall, fullOnFoil, None)
    val known = view.nodes.map(_.ref).toSet
    assert(foreign.result.candidateAnchors.values.flatten.forall(known.contains))
    val mismatch = PopulationAggregate.of(view, Vector(foreign))
    assert(mismatch.isLeft, "a foreign-view proof must be refused regardless of error shape")
    mismatch match
      case Left(AlignError.PopulationViewMismatch(subject, proof, aggregate)) =>
        assertEquals(subject, sid("s-foreign"))
        assertEquals(proof, foilView.contentFingerprint)
        assertEquals(aggregate, view.contentFingerprint)
      case other => fail(s"expected the view check to run first, got $other")
  }

  test("same-view law: a subject whose recall is not the recall of its proof is refused") {
    val wrongRecall = SubjectAlignment(sid("s-wrong"), AnnaFixture.summary.recall, full, None)
    val r = PopulationAggregate.of(view, Vector(wrongRecall))
    assert(r.isLeft, "a proof/recall mismatch must be refused regardless of error shape")
    r match
      case Left(AlignError.PopulationRecallMismatch(subject, proof, suppliedRecall)) =>
        assertEquals(subject, sid("s-wrong"))
        assertEquals(proof, full.recallChecksum)
        assertEquals(suppliedRecall, AlignWire.recallChecksum(AnnaFixture.summary.recall))
      case other => fail(s"expected a typed population-recall mismatch, got $other")
  }

  test("receipt keeps each subject bound to its recall and derives every summary") {
    val rc = real.receipt
    assertEquals(rc.viewFingerprint, view.contentFingerprint)
    assertEquals(rc.subjectCount, 3)
    val expected = Vector(
      PopulationMemberReceipt(
        sid("s-full"),
        AlignWire.recallChecksum(AnnaFixture.recall),
        unitPresence = true
      ),
      PopulationMemberReceipt(
        sid("s-summary"),
        AlignWire.recallChecksum(AnnaFixture.summary.recall),
        unitPresence = true
      ),
      PopulationMemberReceipt(
        sid("s-unranked"),
        AlignWire.recallChecksum(AnnaFixture.recall),
        unitPresence = true
      )
    )
    assertEquals(rc.members.toVector, expected)
    assertEquals(rc.recallChecksums, expected.map(_.recallChecksum))
    assertEquals(rc.subjectCount, rc.members.length)
    assertEquals(rc.subjectsWithNoRecallUnits, Vector.empty)
  }

  test("receipt construction canonicalizes subject order") {
    val reversed = NonEmptyVector
      .fromVector(real.receipt.members.toVector.reverse)
      .getOrElse(fail("the real receipt must contain members"))
    val rebuilt = PopulationReceipt.from(real.receipt.viewFingerprint, reversed)
    assertEquals(rebuilt, real.receipt)
    assertEquals(rebuilt.members.toVector.map(_.subject), real.subjectIds)
  }

  test("a non-empty transcript with no recall units is counted and named exactly") {
    // HsmmResult.validated accepts a recall with no units. Its non-empty transcript proves that the
    // observable fact is only "no segmented recall units", not that the participant was silent.
    val transcript = StorySource.fromText("nothing here.", Some("no-units")).toOption.get
    val zeroUnitRecall =
      RecallGraph(
        transcript,
        SurfaceAnalyzer.analyze(transcript),
        Vector.empty,
        RecallRelations.empty
      )
    val zeroUnitProof = HsmmResult
      .validated(
        zeroUnitRecall,
        view,
        Map.empty,
        AlignmentMatrix.of(Vector.empty).toOption.get,
        TransitionFlow(Vector.empty),
        Vector.empty,
        -1.0,
        Map.empty,
        0
      )
      .fold(e => fail(s"a zero-unit recall should still validate: ${e.message}"), identity)
    val withNoUnits = PopulationAggregate
      .of(
        view,
        real.subjects :+
          SubjectAlignment(sid("s-no-units"), zeroUnitRecall, zeroUnitProof, None)
      )
      .fold(e => fail(e.message), identity)
    assertEquals(withNoUnits.receipt.subjectCount, 4)
    assertEquals(withNoUnits.receipt.subjectsWithNoRecallUnits, Vector(sid("s-no-units")))
    assertEquals(
      withNoUnits.receipt.members.toVector.find(_.subject == sid("s-no-units")).map(_.unitPresence),
      Some(false)
    )
    // The zero-unit subject contributes no mass, so grounded-subject counts are unchanged.
    assertEquals(withNoUnits.groundedSubjects, real.groundedSubjects)
  }

  test("receipt identity changes when the same recalls are assigned to different subjects") {
    def aggregate(subjects: Vector[SubjectAlignment]): PopulationAggregate =
      PopulationAggregate.of(view, subjects).fold(e => fail(e.message), identity)
    val original = aggregate(
      Vector(
        SubjectAlignment(sid("a"), AnnaFixture.recall, full, None),
        SubjectAlignment(sid("b"), AnnaFixture.summary.recall, summary, None)
      )
    )
    val swapped = aggregate(
      Vector(
        SubjectAlignment(sid("a"), AnnaFixture.summary.recall, summary, None),
        SubjectAlignment(sid("b"), AnnaFixture.recall, full, None)
      )
    )
    assertEquals(
      original.receipt.recallChecksums.sortBy(_.hex),
      swapped.receipt.recallChecksums.sortBy(_.hex),
      "the old independently sorted checksum field would collide"
    )
    assertNotEquals(original.receipt, swapped.receipt)
    assertNotEquals(original.receipt.members, swapped.receipt.members)
  }

  test("receipt and aggregate are invariant under subject order") {
    val reversed = PopulationAggregate
      .of(view, real.subjects.reverse)
      .fold(e => fail(e.message), identity)
    assertEquals(reversed.receipt, real.receipt)
    assertEquals(reversed, real)
    assertEquals(reversed.visitationMatrix, real.visitationMatrix)
  }

  test("population rendering is summary-only, never a dump of subjects or recalls") {
    val renderedAggregate = real.toString
    val renderedReceipt = real.receipt.toString
    assert(renderedAggregate.contains("subjects=3"), renderedAggregate)
    assert(renderedReceipt.contains("subjects=3"), renderedReceipt)
    assert(renderedReceipt.contains("noRecallUnits=0"), renderedReceipt)
    real.subjectIds.foreach { subject =>
      assert(!renderedAggregate.contains(subject.value), renderedAggregate)
      assert(!renderedReceipt.contains(subject.value), renderedReceipt)
    }
    real.receipt.recallChecksums.foreach { checksum =>
      assert(!renderedReceipt.contains(checksum.hex), renderedReceipt)
    }
  }

  // ---- generators for properties: real gated inferences under varied configurations ---------
  //
  // Every property input is produced by GraphHsmm.infer, so admissibility records originate in the
  // mode gate (never by hand) and the proof invariants hold by construction.

  private def inferWith(
      recall: storymodel4s.recall.RecallGraph,
      cands: Candidates,
      model: LocalCostModel,
      temperature: Double,
      passes: Int
  ): HsmmResult =
    GraphHsmm
      .infer(
        recall,
        view,
        cands,
        model,
        HsmmConfig.unsafe(temperature = temperature, refinementPasses = passes)
      )
      .fold(e => fail(e.message), identity)

  /** `allowExternal = false` restricts to recalls whose every unit has ranked candidates, so the
    * flow is source→source dominated; external mass can still be small but nonzero.
    */
  private def genResult(
      allowExternal: Boolean
  ): Gen[(storymodel4s.recall.RecallGraph, HsmmResult)] =
    val temps = Gen.oneOf(0.05, 0.15, 0.5)
    val passes = Gen.oneOf(0, 1)
    val anchored = for
      t <- temps
      k <- passes
      f <- Gen.oneOf(Vector(None, Some(AnnaFixture.summary), Some(AnnaFixture.blended)))
    yield f match
      case None =>
        AnnaFixture.recall ->
          inferWith(AnnaFixture.recall, AnnaFixture.candidates, AnnaFixture.costModel, t, k)
      case Some(x) => x.recall -> inferWith(x.recall, x.candidates, x.costModel, t, k)
    if allowExternal then
      Gen.frequency(4 -> anchored, 1 -> Gen.const(AnnaFixture.recall -> unranked))
    else anchored

  private def genPopulation(allowExternal: Boolean): Gen[PopulationAggregate] =
    for
      k <- Gen.choose(1, 4)
      results <- Gen.listOfN(k, genResult(allowExternal))
    yield PopulationAggregate
      .of(
        view,
        results.zipWithIndex.map { case ((g, r), i) =>
          SubjectAlignment(sid(s"p$i"), g, r, None)
        }.toVector
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

  property("flow row sums plus source→external flow equal summed non-final posteriors") {
    // Marginal consistency (proved by HsmmResult.validated) means the population's source→source
    // flow out of v is exactly the non-final posterior mass on v minus what left for the externals.
    forAll(genPopulation(allowExternal = false)) { p =>
      p.nodeRefs.forall { v =>
        val out = p.populationFlow.collect { case ((a, _), m) if a == v => m }.sum
        val toExternal = p.subjects.map { s =>
          s.result.flow.steps.map { st =>
            st.mass.collect { case ((a, b), m) if a.anchor.contains(v) && b.isExternal => m }.sum
          }.sum
        }.sum
        val exact = p.subjects.map { s =>
          s.result.posterior.rows.dropRight(1).map(_.sourceMassOn(v)).sum
        }.sum
        math.abs(out + toExternal - exact) <= 1e-6
      }
    }
  }

  property("an all-external subject changes only external masses and coverage") {
    forAll { (p: PopulationAggregate) =>
      // The unrankable recall is a genuine all-external subject (every row is Unranked).
      val extResult = unranked
      assert(extResult.posterior.rows.forall(_.sourceMass == 0.0))
      val q = PopulationAggregate
        .of(
          view,
          p.subjects :+ SubjectAlignment(sid("zz-ext"), AnnaFixture.recall, extResult, None)
        )
        .fold(e => throw new AssertionError(e.message), identity)
      val nodesSame = p.nodeRefs.forall { v =>
        q.columnMass(v) == p.columnMass(v) && q.expectedVisits(v) == p.expectedVisits(v) &&
        q.visitationRate(v).rate == p.visitationRate(v).rate
      }
      nodesSame && q.populationFlow == p.populationFlow && q.hubs(3) == p.hubs(3) &&
      q.groundedSubjects == p.groundedSubjects &&
      q.visitationRate(p.nodeRefs.head).coverage.eligible ==
        p.visitationRate(p.nodeRefs.head).coverage.eligible + 1 &&
        q.externalMass(sid("zz-ext")).exists { m =>
          m.getOrElse(ExternalState.Unranked, 0.0) > 0.0 &&
          math.abs(m.values.sum - m.getOrElse(ExternalState.Unranked, 0.0)) <= 1e-9
        }
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
