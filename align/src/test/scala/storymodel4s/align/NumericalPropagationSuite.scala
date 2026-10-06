package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite
import scala.collection.immutable.ListMap
import storymodel4s.core.Checksum

/** Keeps arithmetic-order and exact identity consequences observable after portable inference. */
class NumericalPropagationSuite extends FunSuite:
  import AnnaFixture.{recall, view, candidates, costModel}
  private def bits(x: Double): Long = java.lang.Double.doubleToLongBits(x)

  test("same-anchor mode totals are bit-identical across opposing input map orders") {
    val ref = view.leaves.head.ref
    val states = Facet.values.take(3).toVector.map(f =>
      AlignState.Distorted(ref, NonEmptySet.of(f))
    ).sortBy(_.key)
    val tiny = java.lang.Double.longBitsToDouble(0x3c90000000000000L) // 2^-54
    val values = Vector(states(0) -> 0.5, states(1) -> tiny, states(2) -> tiny)
    // The controls prove this fixture discriminates accumulation order before invoking production.
    assertNotEquals(bits(values.map(_._2).sum), bits(values.reverse.map(_._2).sum))
    def matrix(xs: Vector[(AlignState, Double)]) = AlignmentMatrix.of(
      Vector(AlignmentRow.of(recall.ordered.head.id, ListMap.from(xs)).toOption.get)
    ).toOption.get
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

  test("numerical producer revision is distinct from configuration identity") {
    assertEquals(AlignmentMath.Revision, "alignment-fdlibm/exp-log-v1")
    assertNotEquals(Checksum.ofText(AlignmentMath.Revision), HsmmConfig.default.fingerprint)
  }
