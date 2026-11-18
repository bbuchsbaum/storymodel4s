package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite
import storymodel4s.recall.*

/** Emits the numeric directed-relation witness consumed by the Python port regression. */
class RelationPreservationParityProbe extends FunSuite:
  import AnnaFixture.*

  private def right[A](value: Either[AlignError, A]): A = value.fold(e => fail(e.message), identity)

  private val distortedE3 = AlignState.Distorted(e3, NonEmptySet.one(Facet.Polarity))
  private val fromUnit = u3.copy(ordinal = 0)
  private val toUnit = u2.copy(ordinal = 1)
  private val u3Row = right(AlignmentRow.of(u3.id, Map(
    AlignState.Source(e2) -> 0.2,
    distortedE3 -> 0.3,
    AlignState.External(ExternalState.Association) -> 0.5
  )))
  private val u2Row = right(AlignmentRow.of(u2.id, Map(
    AlignState.Source(e5) -> 0.4,
    AlignState.Source(e1) -> 0.2,
    AlignState.External(ExternalState.Intrusion) -> 0.4
  )))
  private val posterior = right(AlignmentMatrix.of(Vector(u3Row, u2Row)))
  private val twoUnitRecall = RecallGraph.validated(
    transcript,
    rAtlas,
    Vector(fromUnit, toUnit),
    RecallRelations(
      Vector(RecallTemporalEdge(fromUnit.id, RecallTemporalRelation.Before, toUnit.id, None)),
      Vector(RecallCausalEdge(fromUnit.id, toUnit.id, None)),
      Vector.empty,
      Vector.empty
    )
  ).fold(errors => fail(errors.toString), identity)
  private val base = Vector(
    Map(AlignState.Source(e2) -> 10.0, distortedE3 -> 20.0,
      AlignState.External(ExternalState.Association) -> 30.0),
    Map(AlignState.Source(e5) -> 40.0, AlignState.Source(e1) -> 50.0,
      AlignState.External(ExternalState.Intrusion) -> 60.0)
  )

  test("directed relation-preservation witness") {
    assert(view.reachable(RelationLayer.WorldTime, e2, e5))
    assert(view.reachable(RelationLayer.WorldTime, e3, e5))
    assert(!view.reachable(RelationLayer.WorldTime, e5, e2))
    assert(view.reachable(RelationLayer.Causal, e2, e5))
    assert(!view.reachable(RelationLayer.Causal, e3, e5))
    val diagnostic = RelationPreservation.diagnostic(posterior, twoUnitRecall, view)
    val reweighted = RelationPreservation.reweight(
      base, Vector(fromUnit, toUnit), posterior, twoUnitRecall, view, lambda = 0.5)
    val world = diagnostic(RelationLayer.WorldTime)
    val causal = diagnostic(RelationLayer.Causal)
    assertEqualsDouble(world.mean.get, 2.0 / 3.0, 1e-12)
    assertEqualsDouble(causal.mean.get, 4.0 / 15.0, 1e-12)
    assertEquals((world.evaluated, world.stated), (1, 1))
    assertEquals((causal.evaluated, causal.stated), (1, 1))
    assertEqualsDouble(reweighted(0)(AlignState.Source(e2)), 9.6, 1e-12)
    assertEqualsDouble(reweighted(0)(distortedE3), 19.8, 1e-12)
    assertEqualsDouble(reweighted(0)(AlignState.External(ExternalState.Association)), 30.0, 1e-12)
    assertEqualsDouble(reweighted(1)(AlignState.Source(e5)), 39.65, 1e-12)
    assertEqualsDouble(reweighted(1)(AlignState.Source(e1)), 50.0, 1e-12)
    assertEqualsDouble(reweighted(1)(AlignState.External(ExternalState.Intrusion)), 60.0, 1e-12)
    def layerJson(layer: LayerPreservation): String =
      s"{\"stated\":${layer.stated},\"evaluated\":${layer.evaluated},\"mean\":${layer.mean.get}}"
    def cost(unit: Int, state: AlignState): Double = reweighted(unit)(state)
    val actual =
      s"""{\"preservation\":{\"world-time\":${layerJson(world)},\"causal\":${layerJson(causal)}},""" +
        s"""\"final_costs\":{\"u3\":{\"e2-hear:faithful\":${cost(0, AlignState.Source(e2))},""" +
        s"""\"e3-search:distorted:Polarity\":${cost(0, distortedE3)},""" +
        s"""\"external:Association\":${cost(0, AlignState.External(ExternalState.Association))}},""" +
        s"""\"u2\":{\"e5-find:faithful\":${cost(1, AlignState.Source(e5))},""" +
        s"""\"e1-arrive:faithful\":${cost(1, AlignState.Source(e1))},""" +
        s"""\"external:Intrusion\":${cost(1, AlignState.External(ExternalState.Intrusion))}}}}"""
    println("RELATION_PRESERVATION_PARITY=" + actual)
  }
