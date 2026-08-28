package storymodel4s.features

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import storymodel4s.core.*

class StructureSuite extends ScalaCheckSuite:
  import Fixtures.*

  private def sp(s: String) = FeatureSpaceId.unsafe(s)
  private def deriv(inputs: FeatureSpaceId*): FeatureDerivation =
    FeatureDerivation(
      NonEmptyVector.fromVectorUnsafe(inputs.toVector),
      Some(WindowPlan.words(20, 5)),
      ScalarReducer.Mean.id,
      WeightingPolicy.Uniform,
      MissingValuePolicy.IgnoreMissing,
      None,
      "test-1"
    )

  test("derivation ids are content-addressed and sensitive to every field") {
    val d = deriv(sp("a"))
    assertEquals(d.derivationId, deriv(sp("a")).derivationId)
    assertNotEquals(d.derivationId, d.copy(reducer = ScalarReducer.Sum.id).derivationId)
    assertNotEquals(d.derivationId, d.copy(window = Some(WindowPlan.words(20, 10))).derivationId)
    assertNotEquals(d.derivationId, d.copy(missing = MissingValuePolicy.Fail).derivationId)
    assertNotEquals(
      d.derivationId,
      d.copy(normalization = Some(NormalizationPolicy.ZScore("pop"))).derivationId
    )
    assertNotEquals(d.derivationId, d.copy(implementationVersion = "test-2").derivationId)
    assert(d.outputSpaceId.value.startsWith("a/"))
  }

  test("derivation graph: ancestors, stale descendants, cycle and self rejection") {
    val g = DerivationGraph.empty
      .add(sp("smooth"), deriv(sp("raw")))
      .toOption
      .get
      .add(sp("slope"), deriv(sp("smooth")))
      .toOption
      .get
      .add(sp("mix"), deriv(sp("slope"), sp("other")))
      .toOption
      .get
    assertEquals(g.ancestors(sp("mix")), Set(sp("slope"), sp("smooth"), sp("raw"), sp("other")))
    assertEquals(g.staleDescendants(sp("raw")), Set(sp("smooth"), sp("slope"), sp("mix")))
    assertEquals(g.staleDescendants(sp("other")), Set(sp("mix")))
    assertEquals(g.staleDescendants(sp("mix")), Set.empty)
    assert(g.isRaw(sp("raw")) && !g.isRaw(sp("smooth")))
    assert(g.add(sp("raw"), deriv(sp("mix"))).isLeft) // would close a cycle
    assert(g.add(sp("self"), deriv(sp("self"))).isLeft)
    assert(g.add(sp("smooth"), deriv(sp("raw"))).isLeft) // duplicate output
  }

  property("derivation graph never admits a cycle") {
    val names = Gen.listOfN(6, Gen.identifier.map(_.take(4))).map(_.distinct)
    forAll(
      names,
      Gen.listOfN(12, Gen.chooseNum(0, 5).flatMap(a => Gen.chooseNum(0, 5).map(b => (a, b))))
    ) { (ns, pairs) =>
      val g = pairs.foldLeft(DerivationGraph.empty) { case (acc, (a, b)) =>
        if a < ns.size && b < ns.size then acc.add(sp(ns(a)), deriv(sp(ns(b)))).getOrElse(acc)
        else acc
      }
      ns.forall(n => !g.ancestors(sp(n)).contains(sp(n)))
    }
  }

  test(
    "circularity: imageability used to induce scene boundaries, then tested at those boundaries"
  ) {
    val raw = sp("imageability.demo")
    val smooth = sp("imageability.demo/abc")
    val graph = DerivationGraph.empty.add(smooth, deriv(raw)).toOption.get
    val stage = StageId.unsafe("hierarchy")
    val ledger = FeatureUseLedger.empty
      .record(
        FeatureUse(UsePurpose.Induction(stage), Set(smooth, sp("semantic.contextual")), Set.empty)
      )
      .record(FeatureUse(UsePurpose.Exploratory, Set(sp("affect")), Set.empty))
    // testing the raw track against boundaries built from its smoothed derivative is circular
    val w = CircularityCheck.dependsOn(Set(raw), ledger.inductionUses, graph)
    assert(w.isDefined)
    assertEquals(w.get.inductionStages, Set(stage))
    assert(w.get.message.contains("hierarchy"))
    // a feature that was not used for induction is fine
    assertEquals(CircularityCheck.dependsOn(Set(sp("affect")), ledger.inductionUses, graph), None)
    // explicitly excluding the feature from induction clears it
    val withheld = FeatureUseLedger.empty.record(
      FeatureUse(UsePurpose.Induction(stage), Set(smooth, sp("semantic.contextual")), Set(smooth))
    )
    assertEquals(CircularityCheck.dependsOn(Set(raw), withheld.inductionUses, graph), None)
    // no induction uses at all: nothing to be circular with
    assertEquals(CircularityCheck.dependsOn(Set(raw), Vector.empty, graph), None)
    assertEquals(ledger.size, 2)
  }

  test("boundary belief input: weights are parameters; missing signals contribute nothing") {
    val gap: FeatureTarget.Boundary = FeatureTarget.Boundary(atlas.sentences(1).id)
    val ev = BoundaryEvidence(
      gap,
      Map(
        BoundarySignal.Semantic -> Estimate.observed(0.8),
        BoundarySignal.Location -> Estimate.observed(1.0),
        BoundarySignal.Imageability -> Estimate.Missing(MissingReason.Excluded)
      ),
      Set(sp("semantic.contextual"), sp("imageability.demo")),
      level = 1
    )
    val weights = BoundaryBeliefInput(
      Map(
        1 -> Map(
          BoundarySignal.Semantic -> 2.0,
          BoundarySignal.Location -> 1.0,
          BoundarySignal.Imageability -> 5.0
        )
      )
    )
    assertEqualsDouble(weights.rawScore(ev).get, 2.6, 1e-9)
    assert(weights.rawScore(ev.copy(level = 2)).isEmpty)
    assertEquals(weights.usedSpaces(ev), ev.inputSpaces)
    assert(BoundaryBeliefInput(Map(1 -> Map(BoundarySignal.Goals -> 1.0))).rawScore(ev).isEmpty)
  }

  test("world-time transitions carry uncertainty instead of manufactured precision") {
    val unresolved = WorldTimeTransition.Unresolved(
      Vector(
        TemporalHypothesis("before", Credence.unsafeRaw(0.4)),
        TemporalHypothesis("after", Credence.unsafeRaw(0.3))
      )
    )
    assert(!unresolved.isBackward)
    val back = WorldTimeTransition.JumpBackward(
      Some(DurationEstimate(Estimate.observed(3.0), DurationUnit.Years, None))
    )
    assert(back.isBackward)
    assert(!WorldTimeTransition.ReturnFromEarlierFrame.isBackward)
    val all: Vector[WorldTimeTransition] = Vector(
      WorldTimeTransition.Continues,
      WorldTimeTransition.JumpForward(None),
      back,
      WorldTimeTransition.ReturnFromEarlierFrame,
      WorldTimeTransition.SimultaneousThreadSwitch,
      WorldTimeTransition.Atemporal,
      unresolved
    )
    assertEquals(all.count(_.isBackward), 1)
  }

  test("sidecar manifests and refs are validated") {
    val m = SidecarManifest(sp("semantic.surface"), 384, 10, Dtype.Float32, Checksum.ofText("x"))
    assert(SidecarManifest.validated(m).isRight)
    assertEquals(m.expectedByteLength, 384L * 10 * 4)
    assert(SidecarManifest.validated(m.copy(dimension = 0)).isLeft)
    val ok = FeatureRef(FeatureTarget.Sentence(atlas.sentences(0).id), m.space, 9)
    assert(FeatureRef.validated(ok, m).isRight)
    assert(FeatureRef.validated(ok.copy(row = 10), m).isLeft)
    assert(FeatureRef.validated(ok.copy(space = sp("other")), m).isLeft)
    assert(FeatureRef.validatedAll(Vector(ok, ok.copy(row = 0)), Map(m.space -> m)).isRight)
    assert(FeatureRef.validatedAll(Vector(ok.copy(space = sp("other"))), Map(m.space -> m)).isLeft)
  }

  test("coverage arithmetic and estimate mapping") {
    assertEqualsDouble(Coverage(4, 1).fraction, 0.25, 1e-12)
    assertEquals(Coverage(4, 1) + Coverage(2, 2), Coverage(6, 3))
    assertEquals(Coverage.empty.fraction, 0.0)
    intercept[IllegalArgumentException](Coverage(1, 2))
    assertEquals(Estimate.observed(2.0).map(_ * 2).toOption, Some(4.0))
    assertEquals(
      Estimate.Missing[Double](MissingReason.Unknown).map(_ * 2),
      Estimate.Missing(MissingReason.Unknown)
    )
  }

  test("feature targets order positionally for tokens and windows") {
    val ts: Vector[FeatureTarget] = Vector(
      FeatureTarget.Window(TokenRange.unsafe(5, 9)),
      FeatureTarget.Token(TokenIndex.unsafe(7)),
      FeatureTarget.Token(TokenIndex.unsafe(2)),
      FeatureTarget.Sentence(SurfaceUnitId.unsafe("s1"))
    )
    assertEquals(ts.sorted.map(FeatureTarget.rankOf), Vector(0, 0, 1, 2))
    assertEquals(ts.sorted.head, FeatureTarget.Token(TokenIndex.unsafe(2)))
  }
