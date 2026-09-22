package storymodel4s.codec

import munit.FunSuite
import storymodel4s.align.TemporalQuery
import storymodel4s.core.*
import storymodel4s.recall.RecallRef
import storymodel4s.view.TemporalPartitionView

class TemporalPartitionViewSuite extends FunSuite:
  private def partition: TemporalQuery.Partition =
    val readout = TemporalQueryFixture.readout()
    val axis = readout.region.axis
    val regions = Vector(
      TemporalQuery.Region.on(axis, Vector(10L -> 12L, 14L -> 15L), Vector(18L)).toOption.get,
      TemporalQuery.Region.on(axis, Vector(15L -> 18L), Vector.empty).toOption.get
    )
    readout.prepared.partition(regions).toOption.get

  test("view retains exact regions original contributions and all accounting without arithmetic") {
    val p = partition
    val view = TemporalPartitionView.from(p)
    assert(view.partition eq p)
    assert(view.ledger eq p.prepared.ledger)
    assertEquals(view.measure, TemporalQuery.Measure.NormalizedScoreMass)
    assertEquals(view.allocatedOutside, 0.2)
    assertEquals(view.ledger.unavailableLocation, 0.25)
    assertEquals(view.ledger.external.map(_._2).sum, 0.25)
    assertEquals(view.bins.map(_.readout.allocatedRegionMass), Vector(0.15, 0.15))
    view.bins.zip(p.bins).foreach { (bin, original) =>
      assert(bin.readout eq original)
      assert(bin.region eq original.region)
      assertEquals(bin.cells.map(_.contribution), original.contributions)
    }
    val geometry = view.bins.head.region.geometry.get
    assertEquals(
      geometry.intervals.map(i => i.start -> i.endExclusive),
      Vector(10L -> 12L, 14L -> 15L)
    )
    assertEquals(geometry.points.map(_.at), Vector(18L))
    val ranked = view.bins.head.topK(1).toOption.get
    assertEquals(ranked.retained.map(_.component.weight), Vector(0.5))
    assertEquals(ranked.omittedWeight, 0.5)
    assertEquals(view.allocationDiscrepancy, p.allocationDiscrepancy)
  }

  test("navigation requires mapping identity and never creates posterior cell states") {
    val view = TemporalPartitionView.from(partition)
    val address = Addressable[RecallRef].address(RecallRef.Unit(view.unit))
    val cells = view.cellsFor(view.mappingDigest, address).get
    assertEquals(cells.size, 6)
    assert(cells.forall(_.alternative.isInstanceOf[TemporalQuery.Alternative.Score]))
    assertEquals(view.cellsFor(Checksum.ofText("another-mapping"), address), None)
    val target = cells.flatMap(_.target).head
    assertEquals(view.cellsForTarget(view.mappingDigest, target).get.size, 2)
    assertEquals(view.cellsForTarget(Checksum.ofText("another-mapping"), target), None)
    assertEquals(cells.map(_.mark).distinct.size, cells.size)
  }

  test("view identities preserve bin order and empty partitions retain the original ledger") {
    val p = partition
    val forward = TemporalPartitionView.from(p)
    val reversed =
      TemporalPartitionView.from(p.prepared.partition(p.bins.map(_.region).reverse).toOption.get)
    assertNotEquals(forward.digest, reversed.digest)
    assertEquals(forward.digest, TemporalPartitionView.from(p).digest)
    assertEquals(
      forward.bins
        .flatMap(_.cells)
        .map(_.mark)
        .intersect(reversed.bins.flatMap(_.cells).map(_.mark)),
      Vector.empty
    )
    val empty = TemporalPartitionView.from(p.prepared.partition(Vector.empty).toOption.get)
    assertEquals(empty.bins, Vector.empty)
    assertEquals(empty.allocatedOutside, 0.5)
    assertEquals(empty.ledger.unavailableLocation, 0.25)
  }
