package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.PolarityTag

class StructuralWeightSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  test("exact reduced fractions refuse malformed nonfinite and unreduced declarations") {
    assertEquals(right(StructuralWeight.parse("1/3")).toString, "1/3")
    Vector(
      "NaN",
      "Infinity",
      "0.5",
      "1/0",
      "2/1",
      "-1/2",
      "2/4",
      "00/1",
      "1/01",
      " 1/2",
      "1/2 ",
      "9223372036854775808/9223372036854775809"
    )
      .foreach(s => assert(StructuralWeight.parse(s).isLeft, s))
    assert(StructuralWeight.of(Long.MinValue, 1).isLeft)
    assert(StructuralWeight.of(0, Long.MinValue).isLeft)
  }

  test("rounded interior coefficient never becomes an endpoint or loses its identity") {
    val near = right(StructuralWeight.of(Long.MaxValue - 1, Long.MaxValue))
    assertEquals(near.coefficient, 1.0)
    assert(!near.isReference && !near.isReconstruction)
    assertNotEquals(
      right(StructuralProfile.of(near)).fingerprint,
      right(StructuralProfile.of(StructuralWeight.Reconstruction)).fingerprint
    )
    assert(right(StructuralWeight.parse("0/1")).isReference)
    assert(right(StructuralWeight.parse("1/1")).isReconstruction)
  }

  test("interior scales every named transition including external coefficients") {
    val profile = right(StructuralProfile.of(right(StructuralWeight.of(1, 2))))
    TransitionKind.values.foreach { kind =>
      assertEquals(
        profile.inference.transitions(kind),
        profile.preset.inference.transitions(kind) * 0.5
      )
    }
    assertEquals(profile.inference.temperature, 0.15)
    assertEquals(profile.inference.refinementPasses, 0)
  }

  private def execution(external: Double = 0.6, uniformSources: Boolean = false) =
    import ReferenceFixture.{right as _, *}
    val r = graph(
      Vector(
        unit,
        AnnaFixture.u1.copy(proposition = unit.proposition),
        AnnaFixture.u2.copy(proposition = unit.proposition)
      )
    )
    val v = view.copy(nodes =
      view.nodes.map(_.copy(scoringPosition = None, polarity = PolarityTag.Positive))
    )
    val e = evidence(
      table(
        r,
        v,
        (u, ref) =>
          if uniformSources then 1.0 else if (u == r.ordered(1).id) == (ref == a) then 0.1 else 0.4
      ),
      r,
      v,
      external = external
    )
    right(
      MappingRun.compute(
        r,
        v,
        MappingMeasureFixture.inventory(r),
        MappingMeasureFixture.source(v),
        e,
        profile(v),
        StructuredReconstruction.Profile.sherlock(),
        right(StructuredReconstruction.Grouping.of(v, Vector(a -> 0, b -> 1)))
      )
    )

  test("endpoint selections return the identical retained endpoint records") {
    val e = execution()
    assert(right(right(e.weighted(StructuralWeight.Reference)).record) eq right(e.referenceRecord))
    assert(
      right(right(e.weighted(StructuralWeight.Reconstruction)).record) eq right(
        e.reconstructionRecord
      )
    )
    assert(right(e.weighted(StructuralWeight.Reference)).inference.isEmpty)
    assert(right(e.weighted(StructuralWeight.Reconstruction)).inference.isEmpty)
  }

  test(
    "three-unit exhaustive oracle checks every marginal and transition including external states"
  ) {
    import ReferenceFixture.{a, b}
    val e = execution()
    val selected = right(e.weighted(right(StructuralWeight.of(1, 2))))
    val actual = selected.inference.get
    val states = Vector(AlignState.Source(a), AlignState.Source(b)) ++ AlignState.externals
    assertEquals(e.reference.evidence.states.map(_.toSet), Vector.fill(3)(states.toSet))
    def cost(i: Int, s: AlignState): Double =
      if s.isExternal then 0.6
      else if (i == 1) == s.anchor.contains(a) then 0.1
      else 0.4
    // Independent fixture has no edges, measured positions or hierarchy. Only Stay fires.
    val enter = 1.0 / (1.0 + math.exp(0.75))
    val stayExternal = 1.0 / (1.0 + math.exp(0.425))
    val staySource = math.exp(0.25)
    def transition(s: AlignState, t: AlignState): Double =
      if s.isExternal then if t.isExternal then stayExternal / 5.0 else (1.0 - stayExternal) / 2.0
      else if t.isExternal then enter / 5.0
      else (1.0 - enter) * (if s == t then staySource else 1.0) / (staySource + 1.0)
    val paths = for s <- states; t <- states; u <- states yield Vector(s, t, u)
    val weights = paths.map { p =>
      p -> ((1.0 / 7.0) * math.exp(-(cost(0, p(0)) + cost(1, p(1)) + cost(2, p(2))) / 0.15) *
        transition(p(0), p(1)) * transition(p(1), p(2)))
    }
    val z = weights.map(_._2).sum
    assertEqualsDouble(actual.logLikelihood, math.log(z), 1e-12)
    for i <- 0 until 3; s <- states do
      assertEqualsDouble(
        actual.posterior.rows(i).mass(s),
        weights.collect { case (p, w) if p(i) == s => w }.sum / z,
        1e-12
      )
    for i <- 0 until 2; s <- states; t <- states do
      assertEqualsDouble(
        actual.flow.steps(i)(s, t),
        weights.collect { case (p, w) if p(i) == s && p(i + 1) == t => w }.sum / z,
        1e-12
      )
    val record = right(selected.record)
    assert(record.outcomes.forall(_.decision.exists(_.request == DecisionRequest.RawArgmax)))
    assertEquals(
      record.outcomes.map(_.decision.get.chosen),
      Vector(
        Some(Destination.of(AlignState.Source(b))),
        Some(Destination.of(AlignState.Source(a))),
        Some(Destination.of(AlignState.Source(b)))
      )
    )
  }

  test("interior raw decision may remain external and foreign selection authority refuses") {
    val e = execution(external = 0.0, uniformSources = true)
    val selected = right(e.weighted(right(StructuralWeight.of(1, 2))))
    assert(right(selected.record).outcomes.forall(_.decision.get.chosen.exists {
      case Destination.External(_) => true
      case _                       => false
    }))
    val foreign = execution()
    assertEquals(
      foreign.record(MappingRun.Member.Weighted(selected)),
      Left(MappingRefusal.BindingMismatch("weighted execution"))
    )
  }
