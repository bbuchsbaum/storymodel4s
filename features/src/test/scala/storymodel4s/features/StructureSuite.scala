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
    assertNotEquals(d.derivationId, d.copy(eligibility = Eligibility.AllTokens).derivationId)
    assertNotEquals(d.derivationId, d.copy(targetFamily = Some(TargetFamily.Window)).derivationId)
    assert(d.outputSpaceId.value.startsWith("derived:"))
    assertEquals(d.outputSpaceId.value.length, "derived:".length + 32)
  }

  test("derivation ids are identical on every platform (golden)") {
    // Doubles are rendered by IEEE-754 bit pattern, not Double.toString, so JVM and JS agree.
    val d = FeatureDerivation(
      NonEmptyVector.one(sp("imageability.demo")),
      Some(WindowPlan.words(20, 5)),
      ScalarReducer.Kernel(KernelShape.Gaussian(1.0)).id,
      ScalarReducer.Kernel(KernelShape.Gaussian(1.0)).weighting,
      MissingValuePolicy.RequireMinCoverage(0.5),
      Some(NormalizationPolicy.ZScore("pop")),
      "golden-1"
    )
    assertEquals(CanonicalDouble.render(1.0), "0x3ff0000000000000")
    assertEquals(CanonicalDouble.render(0.5), "0x3fe0000000000000")
    assertEquals(CanonicalDouble.render(0.0), "0x0000000000000000")
    assert(d.canonicalString.contains("kernel(gaussian,0x3ff0000000000000)"))
    assert(d.canonicalString.contains("minCoverage(0x3fe0000000000000)"))
    assertEquals(d.derivationId.hex, GoldenDerivationId)
    // an outputSpaceId chain does not grow: deriving from a derived space keeps a fixed length
    val second = d.copy(inputs = NonEmptyVector.one(d.outputSpaceId))
    assertEquals(second.outputSpaceId.value.length, d.outputSpaceId.value.length)
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

  test("boundary scores are only issued through the ledger, which records per-signal inputs") {
    val gap: FeatureTarget.Boundary = FeatureTarget.Boundary(atlas.sentences(1).id)
    val ev = BoundaryEvidence(
      gap,
      Map(
        BoundarySignal.Semantic -> Estimate.observed(0.8),
        BoundarySignal.Location -> Estimate.observed(1.0),
        BoundarySignal.Imageability -> Estimate.Missing(MissingReason.Excluded)
      ),
      Map(
        BoundarySignal.Semantic -> Set(sp("semantic.contextual")),
        BoundarySignal.Imageability -> Set(sp("imageability.demo"))
      ),
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
    val stage = StageId.unsafe("hierarchy")
    val (ledger, score) = FeatureUseLedger.empty.scoreBoundary(weights, ev, stage)
    assertEqualsDouble(score.get.rawScore, 2.6, 1e-9)
    assertEquals(score.get.target, gap)
    // the missing imageability signal contributed nothing, so its space is not a recorded input
    assertEquals(weights.usedSpaces(ev), Set(sp("semantic.contextual")))
    assertEquals(ledger.size, 1)
    assertEquals(ledger.inductionUses.head.spaces, Set(sp("semantic.contextual")))
    assertEquals(ledger.inductionUses.head.purpose, UsePurpose.Induction(stage))
    assertEquals(score.get.use, ledger.inductionUses.head)
    // the recorded use makes a later analysis of the same feature detectably circular
    assert(
      CircularityCheck.dependsOn(Set(sp("semantic.contextual")), ledger.inductionUses).isDefined
    )
    assertEquals(
      CircularityCheck.dependsOn(Set(sp("imageability.demo")), ledger.inductionUses),
      None
    )
    // no weighted signal observed at this level: no score, and nothing recorded
    val (same, none) = FeatureUseLedger.empty.scoreBoundary(weights, ev.copy(level = 2), stage)
    assert(none.isEmpty && same.size == 0)
    val (_, goals) = FeatureUseLedger.empty
      .scoreBoundary(BoundaryBeliefInput(Map(1 -> Map(BoundarySignal.Goals -> 1.0))), ev, stage)
    assert(goals.isEmpty)
    assertEquals(ev.allInputSpaces, Set(sp("semantic.contextual"), sp("imageability.demo")))
  }

  test("world-time transitions carry uncertainty instead of manufactured precision") {
    val unresolved = WorldTimeTransition.Unresolved(
      Vector(
        TemporalHypothesis(TemporalRelationTag.Before, Credence.unsafeRaw(0.4)),
        TemporalHypothesis(TemporalRelationTag.Overlaps, Credence.unsafeRaw(0.3))
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
    assertEquals(m.expectedByteLength, Right(384L * 10 * 4))
    assert(SidecarManifest.validated(m.copy(dimension = 0)).isLeft)
    val ok = FeatureRef(FeatureTarget.Sentence(atlas.sentences(0).id), m.space, 9)
    assert(FeatureRef.validated(ok, m).isRight)
    assert(FeatureRef.validated(ok.copy(row = 10), m).isLeft)
    assert(FeatureRef.validated(ok.copy(space = sp("other")), m).isLeft)
    assert(FeatureRef.validatedAll(Vector(ok, ok.copy(row = 0)), Map(m.space -> m)).isRight)
    assert(FeatureRef.validatedAll(Vector(ok.copy(space = sp("other"))), Map(m.space -> m)).isLeft)
  }

  test("sidecar byte length rejects the first overflowing Float64 product") {
    val dimension = Int.MaxValue
    val bytesPerValue = 8L
    val lastSafeRowCount = (Long.MaxValue / bytesPerValue / dimension).toInt
    val lastSafe = SidecarManifest(
      sp("semantic.overflow-boundary"),
      dimension,
      lastSafeRowCount,
      Dtype.Float64,
      Checksum.ofText("boundary")
    )
    val firstOverflow = lastSafe.copy(rowCount = lastSafeRowCount + 1)

    assertEquals(
      lastSafe.expectedByteLength,
      Right(dimension.toLong * lastSafeRowCount.toLong * bytesPerValue)
    )
    assert(firstOverflow.expectedByteLength.isLeft)
    assert(SidecarManifest.validated(firstOverflow).isLeft)
  }

  test("coverage arithmetic and estimate mapping") {
    assertEqualsDouble(Coverage.unsafe(4, 1).fraction, 0.25, 1e-12)
    assertEquals(Coverage.unsafe(4, 1) + Coverage.unsafe(2, 2), Coverage.unsafe(6, 3))
    assertEquals(Coverage.empty.fraction, 0.0)
    assert(Coverage.of(1, 2).isLeft)
    assert(Coverage.of(-1, 0).isLeft)
    assert(Coverage.of(3, 3).isRight)
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
    // window ends compare numerically, not lexically
    val w9 = FeatureTarget.Window(TokenRange.unsafe(5, 9))
    val w10 = FeatureTarget.Window(TokenRange.unsafe(5, 10))
    assert(Ordering[FeatureTarget].lt(w9, w10))
  }

  test("surface-unit targets rank after every existing case and order by id") {
    val ts: Vector[FeatureTarget] = Vector(
      FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe("p2")),
      FeatureTarget.Segment(SegmentId.unsafe("g")),
      FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe("p1")),
      FeatureTarget.Token(TokenIndex.unsafe(3))
    )
    assertEquals(ts.sorted.map(FeatureTarget.rankOf), Vector(0, 6, 7, 7))
    assertEquals(ts.sorted.last, FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe("p2")))
    assertEquals(FeatureTarget.rankOf(ts.head), 7)
    assertEquals(TargetFamily.of(ts.head), TargetFamily.SurfaceUnit)
    assertEquals(TargetFamily.values.last, TargetFamily.SurfaceUnit)
  }

  property("feature target keys round-trip for every case: parse(parts(t)) == Some(t)") {
    forAll(featureTarget) { t =>
      FeatureTargetKey.parse(FeatureTargetKey.parts(t)) == Some(t)
    }
  }

  property("accepted key parts render back to themselves: parts(parse(p)) == p") {
    val perturb: Gen[Vector[String] => Vector[String]] = Gen.oneOf(
      (p: Vector[String]) => p,
      (p: Vector[String]) => p.updated(0, p.head.capitalize),
      (p: Vector[String]) => p :+ "extra",
      (p: Vector[String]) => p.take(1),
      (p: Vector[String]) => p.map(x => if x.forall(_.isDigit) then "0" + x else x),
      (p: Vector[String]) => p.map(x => if x.forall(_.isDigit) then "+" + x else x),
      (p: Vector[String]) => p.map(x => if x.forall(_.isDigit) then "-" + x else x)
    )
    forAll(featureTarget.map(FeatureTargetKey.parts), perturb) { (p, f) =>
      val q = f(p)
      FeatureTargetKey.parse(q).forall(t => FeatureTargetKey.parts(t) == q)
    }
  }

  test("non-canonical integer parts are not accepted") {
    assertEquals(FeatureTargetKey.parse(Vector("token", "007")), None)
    assertEquals(FeatureTargetKey.parse(Vector("token", "+7")), None)
    assertEquals(FeatureTargetKey.parse(Vector("window", "1", "02")), None)
    assertEquals(
      FeatureTargetKey.parse(Vector("token", "7")),
      Some(FeatureTarget.Token(TokenIndex.unsafe(7)))
    )
    assertEquals(FeatureTargetKey.parse(Vector("unit", "a b")), None)
    assertEquals(
      FeatureTargetKey.parts(FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe("s:p0"))).head,
      "unit"
    )
  }

  property("observation addresses with any target round-trip through Addressable") {
    val ev = summon[Addressable[FeatureAddress]]
    forAll(spaceId, featureTarget) { (s, t) =>
      val a = FeatureAddress.Observation(s, t)
      val addr = ev.address(a)
      ev.parse(addr) == Some(a) && Address.parse(addr.render).toOption.flatMap(ev.parse) == Some(a)
    }
  }

  test("a surface recipe's canonical string is pinned; a narrative window is a separate slot") {
    val d = FeatureDerivation(
      NonEmptyVector.one(sp("imageability.demo")),
      Some(WindowPlan.words(20, 5)),
      ScalarReducer.Kernel(KernelShape.Gaussian(1.0)).id,
      ScalarReducer.Kernel(KernelShape.Gaussian(1.0)).weighting,
      MissingValuePolicy.RequireMinCoverage(0.5),
      Some(NormalizationPolicy.ZScore("pop")),
      "golden-1"
    )
    assertEquals(
      d.canonicalString,
      "inputs=imageability.demo;" +
        "window=window(width=20,step=5,basis=LexicalTokens,edge=KeepPartial);" +
        "reducer=kernel-gaussian-0x3ff0000000000000;" +
        "weighting=kernel(gaussian,0x3ff0000000000000);" +
        "missing=minCoverage(0x3fe0000000000000);" +
        "normalization=zscore(pop);eligibility=lexical;targets=none;impl=golden-1"
    )
    assertEquals(d.derivationId.hex, GoldenDerivationId)
    assertEquals(d.copy(narrativeWindow = None).derivationId.hex, GoldenDerivationId)
    assert(d.hasSingleWindow)
    val plan = NarrativeWindowPlan.of(2).toOption.get
    val n = d.copy(
      window = None,
      narrativeWindow = Some(plan),
      targetFamily = Some(TargetFamily.Situation)
    )
    assert(n.hasSingleWindow)
    assert(!d.copy(narrativeWindow = Some(plan)).hasSingleWindow)
    assertNotEquals(n.derivationId, d.derivationId)
    assert(n.canonicalString.contains(";window=none;narrativeWindow=narrative(halfWidth=2);"))
    assert(n.canonicalString.endsWith(";targets=situation;impl=golden-1"))
    assertNotEquals(
      n.derivationId,
      n.copy(narrativeWindow = Some(NarrativeWindowPlan.perUnit)).derivationId
    )
    assertEquals(NarrativeWindowPlan.perUnit.halfWidth, 0)
    assert(NarrativeWindowPlan.of(-1).isLeft)
  }

  test("a recipe with both a surface and a narrative window is refused at every boundary") {
    val plan = NarrativeWindowPlan.of(1).toOption.get
    val ok = FeatureDerivation.of(
      NonEmptyVector.one(sp("a")),
      None,
      ScalarReducer.Mean.id,
      WeightingPolicy.Uniform,
      MissingValuePolicy.IgnoreMissing,
      None,
      "test-1",
      narrativeWindow = Some(plan)
    )
    assert(ok.exists(_.hasSingleWindow))
    val both = FeatureDerivation.of(
      NonEmptyVector.one(sp("a")),
      Some(WindowPlan.words(20, 5)),
      ScalarReducer.Mean.id,
      WeightingPolicy.Uniform,
      MissingValuePolicy.IgnoreMissing,
      None,
      "test-1",
      narrativeWindow = Some(plan)
    )
    assert(both.isLeft)
    val raw = deriv(sp("raw")).copy(narrativeWindow = Some(plan))
    assert(FeatureDerivation.validated(raw).isLeft)
    assert(DerivationGraph.empty.add(sp("out"), raw).isLeft)
    assert(DerivationGraph.empty.add(sp("out"), deriv(sp("raw"))).isRight)
  }

  private val GoldenDerivationId: String =
    "c0e730f22d83662f4310fbdba4a1d799bd9badc77ca5657b7e67fc09c2522e45"
