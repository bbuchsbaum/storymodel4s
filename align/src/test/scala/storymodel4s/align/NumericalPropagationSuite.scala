package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite
import scala.collection.immutable.ListMap
import storymodel4s.features.CanonicalDouble

/** Keeps arithmetic-order and exact identity consequences observable after portable inference. */
class NumericalPropagationSuite extends FunSuite:
  import AnnaFixture.{recall, view, candidates, costModel}
  private def bits(x: Double): Long = java.lang.Double.doubleToLongBits(x)

  test("same-anchor mode totals are bit-identical across opposing input map orders") {
    val ref = view.leaves.head.ref
    val states = Facet.values
      .take(3)
      .toVector
      .map(f => AlignState.Distorted(ref, NonEmptySet.of(f)))
      .sortBy(_.key)
    val tiny = java.lang.Double.longBitsToDouble(0x3c90000000000000L) // 2^-54
    val values = Vector(states(0) -> 0.5, states(1) -> tiny, states(2) -> tiny)
    // The controls prove this fixture discriminates accumulation order before invoking production.
    assertNotEquals(bits(values.map(_._2).sum), bits(values.reverse.map(_._2).sum))
    def matrix(xs: Vector[(AlignState, Double)]) = AlignmentMatrix
      .of(
        Vector(AlignmentRow.of(recall.ordered.head.id, ListMap.from(xs)).toOption.get)
      )
      .toOption
      .get
    val a = matrix(values)
    val b = matrix(values.reverse)
    assertEquals(bits(a.columnMass(ref)), bits(b.columnMass(ref)))
    assertEquals(bits(a.distortedColumnMass(ref)), bits(b.distortedColumnMass(ref)))
    assertEquals(bits(a.visitation(ref)), bits(b.visitation(ref)))
  }

  test("one adjacent posterior bit changes result binding and is refused by the old binding") {
    val original = GraphHsmm.infer(recall, view, candidates, costModel).toOption.get
    val binding = MappingMeasureFixture.bind(original)
    val row = original.posterior.rows.head
    val state = row.mass.toVector.sortBy(_._1.key).head._1
    val value = row.mass(state)
    assert(value > 0.0 && value < 1.0)
    val adjacent = java.lang.Double.longBitsToDouble(bits(value) + 1)
    assertEquals(bits(adjacent) - bits(value), 1L)
    val changedRow = AlignmentRow.of(row.unit, row.mass.updated(state, adjacent)).toOption.get
    val posterior = AlignmentMatrix.of(original.posterior.rows.updated(0, changedRow)).toOption.get
    val changed = MappingMeasureFixture.revalidate(original.costs, posterior, original.flow)
    assertNotEquals(MappingBindingRender.result(original), MappingBindingRender.result(changed))
    assertEquals(binding.checkResult(original), Right(()))
    assertEquals(binding.checkResult(changed), Left(MappingRefusal.BindingMismatch("resultDigest")))
  }

  test("capture complete synthetic signature and population readout bits") {
    val result = GraphHsmm.infer(recall, view, candidates, costModel).toOption.get
    val signature = RecallSignature.compute(result, recall, view).toOption.get
    val population = PopulationAggregate
      .of(
        view,
        Vector(SubjectAlignment(SubjectId.unsafe("synthetic-numerical"), recall, result, None))
      )
      .toOption
      .get
    val perNode = view.nodes
      .sortBy(_.ref.key)
      .map(n =>
        (
          n.ref.key,
          population.columnMass(n.ref),
          population.visitation(n.ref),
          population.expectedVisits(n.ref),
          population.visitationRate(n.ref)
        )
      )
    val payload = exact(
      (
        signature,
        perNode,
        population.populationFlow,
        population.totalFlow,
        population.visitationMatrix
      )
    )
    println("NUMERICAL_READOUT=" + MappingRender.digest(Vector(payload)).hex)
    println("NUMERICAL_READOUT_FIELDS=" + payload)
    assertEquals(
      MappingRender.digest(Vector(payload)).hex,
      "612a1d03d494dc39a07fa3e7d75b13bf7ec6b801a02cb87e5779bded16cb948d"
    )
  }

  /** Exhaustive for this fixture's published fields; an unfamiliar carrier fails the capture. */
  private def exact(value: Any): String = value match
    case d: Double => CanonicalDouble.render(d)
    // Scala.js cannot distinguish boxed integer-valued Double from Int at this Any boundary.
    // Every Int is exactly representable in binary64; carrier/field position retains its meaning.
    case i: Int              => CanonicalDouble.render(i.toDouble)
    case l: Long             => s"long:$l"
    case b: Boolean          => s"bool:$b"
    case s: String           => MappingRender.sequence(Vector("string", s))
    case r: MassRatio        => exact((r.value, r.conditioningMass, r.totalMass))
    case w: WeightedCoverage =>
      exact((w.estimate, w.conditioningWeight, w.coverage.eligible, w.coverage.observed))
    case s: StepMass                       => exact((s.perStep, s.comparableSteps, s.totalSteps))
    case m: FidelityMode                   => MappingRender.sequence(Vector("mode", m.render))
    case c: storymodel4s.features.Coverage => exact((c.eligible, c.observed))
    case m: scala.collection.Map[?, ?]     =>
      MappingRender.sequence(
        Vector("map") ++ m.toVector
          .map((k, v) => exact(k) -> exact(v))
          .sortBy(_._1)
          .map((k, v) => MappingRender.sequence(Vector(k, v)))
      )
    case xs: Iterable[?] => MappingRender.sequence(Vector("seq") ++ xs.iterator.map(exact))
    case p: Product      =>
      MappingRender.sequence(Vector("product", p.productPrefix) ++ p.productIterator.map(exact))
    case other => fail(s"unrepresented readout carrier: ${other.getClass.getName}")
