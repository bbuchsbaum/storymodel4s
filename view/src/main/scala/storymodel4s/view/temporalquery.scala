package storymodel4s.view

import storymodel4s.align.{Destination, SourceNodeRef, TemporalQuery}
import storymodel4s.core.*
import storymodel4s.recall.{RecallRef, RecallUnitId}

/** Renderer-neutral projection retaining the checked scientific partition verbatim.
  *
  * Local addresses belong to this mapping, not to an arbitrary workspace. A workspace adapter must
  * establish that mapping identity before binding its contextual addresses. This is not a Voyage
  * scene: supplied normalized scores do not imply an executed posterior inference.
  */
final class TemporalPartitionView private (
    val partition: TemporalQuery.Partition,
    val bins: Vector[TemporalPartitionView.Bin],
    val digest: Checksum
):
  def mappingDigest: Checksum = partition.prepared.mapping.digest
  def unit: RecallUnitId = partition.prepared.unit
  def measure: TemporalQuery.Measure = partition.prepared.measure
  def ledger: TemporalQuery.Ledger = partition.prepared.ledger
  def allocatedOutside: Double = partition.allocatedOutside
  def allocationDiscrepancy: Double = partition.allocationDiscrepancy

  /** None means a foreign mapping; an empty vector means no local navigation target. */
  def cellsFor(mapping: Checksum, address: Address): Option[Vector[TemporalPartitionView.Cell]] =
    Option.when(mapping == mappingDigest)(bins.flatMap(_.cells).filter(_.address == address))

  def cellsForTarget(
      mapping: Checksum,
      target: SourceNodeRef
  ): Option[Vector[TemporalPartitionView.Cell]] =
    Option.when(mapping == mappingDigest)(bins.flatMap(_.cells).filter(_.target.contains(target)))

object TemporalPartitionView:
  val Version: String = "temporal-partition-view/v1"

  final class Cell private[TemporalPartitionView] (
      val mark: MarkId,
      val address: Address,
      val contribution: TemporalQuery.Contribution
  ):
    def alternative: TemporalQuery.Alternative = contribution.component.alternative
    def target: Option[SourceNodeRef] = alternative.destination match
      case Destination.Target(ref) => Some(ref)
      case Destination.External(_) => None

  final class Bin private[TemporalPartitionView] (
      val index: Int,
      val readout: TemporalQuery.Readout,
      val cells: Vector[Cell]
  ):
    def region: TemporalQuery.Region = readout.region
    def topK(k: Int): Either[TemporalQuery.Refusal, TemporalQuery.Ranked] = readout.topK(k)

  def from(partition: TemporalQuery.Partition): TemporalPartitionView =
    val prepared = partition.prepared
    val address = Addressable[RecallRef].address(RecallRef.Unit(prepared.unit))
    // Fixed ASCII version, checksums and integer positions: no arbitrary text is hashed here.
    val identity = Checksum.ofText(
      (Vector(Version, prepared.digest.hex) ++ partition.bins.map(_.digest.hex)).mkString(":")
    )
    val bins = partition.bins.zipWithIndex.map { (readout, binIndex) =>
      val cells = readout.contributions.zipWithIndex.map { (contribution, alternativeIndex) =>
        new Cell(
          MarkId.unsafe(s"$Version:${identity.hex}:$binIndex:$alternativeIndex"),
          address,
          contribution
        )
      }
      new Bin(binIndex, readout, cells)
    }
    new TemporalPartitionView(partition, bins, identity)
