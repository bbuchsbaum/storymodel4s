package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite

class SourceRepresentationSnapshotSuite extends FunSuite:
  private val stable = AnnaFixture.view
  private val target = stable.nodes
    .find(_.ref match
      case SourceNodeRef.Segment(_) => true
      case _                        => false)
    .get
  private val changed = stable.copy(nodes =
    stable.nodes.map(n => if n.ref == target.ref then n.copy(level = n.level + 1) else n)
  )
  private val physical =
    stable.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
  private def captured(): (SourceRepresentation, Int) =
    var reads = 0
    val changing = new SourceView:
      def nodes: Vector[NodeSummary] =
        reads += 1
        if reads == 1 then stable.nodes else changed.nodes
      def node(ref: SourceNodeRef): Option[NodeSummary] = stable.node(ref)
      def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
        stable.adjacency(layer)
      def worldOrder: Option[Map[SourceNodeRef, Int]] = stable.worldOrder
      def scoringLength: Int = stable.scoringLength
    val representation = SourceRepresentation
      .of(
        changing,
        NonEmptyVector.one(BundleEntry.text(AnnaFixture.source.canonicalChecksum)),
        None,
        physical
      )
      .toOption
      .get
    (representation, reads)

  test("source targets and fingerprint use one captured inventory") {
    val (source, reads) = captured()
    assertEquals(reads, 1)
    assertEquals(source.target(target.ref).get.level, target.level)
    assertEquals(source.viewFingerprint, ViewFingerprint.of(stable))
    assertNotEquals(source.viewFingerprint, ViewFingerprint.of(changed))
  }
  test("a changing source cannot bind a second inventory with different levels") {
    val (source, _) = captured()
    val result = GraphHsmm
      .infer(AnnaFixture.recall, changed, AnnaFixture.candidates, AnnaFixture.costModel)
      .toOption
      .get
    val inventory = MappingMeasureFixture.inventory(AnnaFixture.recall)
    assertEquals(
      DerivationBinding.of(result, AnnaFixture.recall, inventory, changed, source).left.toOption,
      Some(MappingRefusal.BindingMismatch("viewFingerprint"))
    )
    assert(
      DerivationBinding
        .of(MappingMeasureFixture.result, AnnaFixture.recall, inventory, stable, source)
        .isRight
    )
  }
