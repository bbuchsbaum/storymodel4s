package storymodel4s.align

import munit.FunSuite
import storymodel4s.core.SituationId
import storymodel4s.recall.*

/** Content/mass invariance is tested separately from identities that intentionally bind inputs. */
class ReferenceIsolationSuite extends FunSuite:
  import ReferenceFixture.*
  private val tolerance = 1e-12
  private def mass(result: LocalReference.Result) =
    result.outcomes.collect { case c: LocalReference.Computed => c.unit -> c.mass }.toMap
  private def compare(left: Map[AlignState, Double], right: Map[AlignState, Double]): Unit =
    assertEquals(left.keySet, right.keySet)
    left.foreach((state, value) => assertEqualsDouble(value, right(state), tolerance))

  test("source storage permutations preserve each state's local mass") {
    val reversed = view.copy(nodes = view.nodes.reverse)
    assertNotEquals(reversed.nodes.map(_.ref), view.nodes.map(_.ref))
    val first = run(evidence(table()))
    val second = run(evidence(table(v = reversed), v = reversed), profile(reversed), v = reversed)
    compare(computed(first).mass, computed(second).mass)
  }

  test("behavioural coordinates world order and adjacency cannot alter local reference") {
    val changed = view.copy(
      nodes = view.nodes.zipWithIndex.map((n, i) => n.copy(discoursePosition = 100 - i)),
      worldOrder = Some(Map(a -> 10, b -> 0)),
      edges = Map(RelationLayer.DiscourseSuccession -> Vector((b, a, 1.0)))
    )
    assertNotEquals(changed.worldOrder, view.worldOrder)
    assertNotEquals(changed.edges, view.edges)
    val e = evidence(table())
    val different = evidence(table(v = changed), v = changed)
    assertNotEquals(e.identity, different.identity)
    assertEquals(e.breakdowns, different.breakdowns)
    compare(computed(run(e)).mass, computed(run(different, profile(changed), v = changed)).mass)
  }

  test("fixed packet order changes bindings but preserves mass after unit reconciliation") {
    val second = AnnaFixture.u1.copy(
      function = DiscourseFunction.EpisodicAssertion,
      proposition = unit.proposition.copy(predicate = Some("walk"), lemmas = Set("walk"))
    )
    val forward = graph(Vector(unit, second))
    val reversed = graph(Vector(unit.copy(ordinal = 1), second.copy(ordinal = 0)))
    assertNotEquals(forward.ordered.map(_.id), reversed.ordered.map(_.id))
    def distance(id: RecallUnitId, ref: SourceNodeRef): Double =
      if id == unit.id then (if ref == a then 0.1 else 0.7)
      else if ref == a then 0.6
      else 0.2
    val e = evidence(table(r = forward, distance = distance), r = forward)
    val re = evidence(table(r = reversed, distance = distance), r = reversed)
    assertNotEquals(e.identity, re.identity)
    val first = mass(run(e, r = forward))
    val next = mass(run(re, r = reversed))
    assertEquals(first.keySet, next.keySet)
    first.foreach((id, values) => compare(values, next(id)))
  }

  test("opaque target rename preserves bits with an identifier-independent denominator") {
    val renamed = Map(
      a -> SourceNodeRef.Situation(SituationId.unsafe("z-renamed")),
      b -> SourceNodeRef.Situation(SituationId.unsafe("a-renamed"))
    )
    val back = renamed.map(_.swap)
    val changed = view.copy(nodes = view.nodes.map(n => n.copy(ref = renamed(n.ref))))
    val temp = 1.0 / math.log(1e16)
    val first = computed(run(evidence(table()), profile(temperature = temp))).mass
    val channel = table(v = changed, distance = (_, ref) => if back(ref) == a then 0.0 else 1.0)
    val later =
      computed(run(evidence(channel, v = changed), profile(changed, temp), v = changed)).mass
    def original(state: AlignState): AlignState = state match
      case AlignState.Source(ref)            => AlignState.Source(back(ref))
      case AlignState.Distorted(ref, facets) => AlignState.Distorted(back(ref), facets)
      case other                             => other
    val reconciled = later.map((s, value) => original(s) -> value)
    assertEquals(first.keySet, reconciled.keySet)
    first.foreach((s, value) =>
      assertEquals(
        java.lang.Double.doubleToLongBits(value),
        java.lang.Double.doubleToLongBits(reconciled(s))
      )
    )
  }

  test("uniform semantic scores do not assert uniform combined costs") {
    val weights = right(CostWeights.of(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    val e =
      evidence(table(distance = (_, _) => 0.0), weights = weights, external = 0.0, distortion = 0.3)
    val row = computed(run(e))
    assertEquals(row.uniformSemantic.head.scoredCount, 2)
    val prices = e.breakdowns.head.toVector.filter(_._1.isSource).map(_._2.total)
    assertEquals(prices.distinct.size, 2, "declared mode adjustment must discriminate this fixture")
    assertEquals(row.information, LocalReference.Information.DistinguishedCosts)
  }

  test("an overflowed unit is retained alongside a computed sibling") {
    val second = AnnaFixture.u1.copy(
      function = DiscourseFunction.EpisodicAssertion,
      proposition = unit.proposition.copy(predicate = Some("walk"), lemmas = Set("walk"))
    )
    val r = graph(Vector(unit, second))
    val channel =
      table(r = r, distance = (id, ref) => if id == unit.id || ref == a then 0.0 else 1.0)
    val e = evidence(channel, r = r, cfg = config(1, TieBudgetRequest.AtMost(1)))
    val result = run(e, r = r)
    assertEquals(result.outcomes.map(_.unit), r.ordered.map(_.id))
    assertEquals(
      result.outcomes.head.asInstanceOf[LocalReference.NotComputed].reason,
      LocalReference.NotComputedReason.TieOverflow
    )
    assert(result.outcomes(1).isInstanceOf[LocalReference.Computed])
    assertEquals(result.outcomes(1).costs, e.breakdowns(1))
  }

  test("shifted normalization survives a tiny temperature with strictly positive minimum cost") {
    val e = evidence(table(distance = (_, ref) => if ref == a then 0.5 else 0.75))
    assertEquals(e.breakdowns.head.values.map(_.total).min, 0.5)
    val row = computed(run(e, profile(temperature = java.lang.Double.MIN_VALUE)))
    assertEquals(row.mass(AlignState.Source(a)), 1.0)
    assertEquals(row.mass.filterNot(_._1 == AlignState.Source(a)).values.toSet, Set(0.0))
    assert(row.mass.values.forall(_.isFinite))
  }
