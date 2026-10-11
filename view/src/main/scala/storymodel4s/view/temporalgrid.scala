package storymodel4s.view

import storymodel4s.align.{TemporalGrid, TemporalQuery, SourceNodeRef, Destination}
import storymodel4s.core.*
import storymodel4s.recall.RecallRef

/** Sparse renderer-neutral cells retain the producer's complete grid and inference-unit address. */
final class TemporalGridView private (val grid: TemporalGrid, val digest: Checksum):
  val address: Address = Addressable[RecallRef].address(RecallRef.Unit(grid.prepared.unit))
  def mappingDigest: Checksum = grid.prepared.mapping.digest
  def mark(bin: Int, alternative: TemporalQuery.Alternative): Option[MarkId] =
    grid.cells.get(bin).flatMap(_.find(_.component.alternative == alternative)).map { cell =>
      val index = grid.prepared.components.indexWhere(_.alternative == cell.component.alternative)
      MarkId.unsafe(s"${TemporalGridView.Version}:${digest.hex}:$bin:$index")
    }
  def cellsFor(
      mapping: Checksum,
      target: SourceNodeRef
  ): Option[Map[Int, Vector[TemporalQuery.Contribution]]] =
    Option.when(mapping == mappingDigest)(grid.cells.toVector.flatMap { (bin, values) =>
      val matches = values.filter(_.component.alternative.destination == Destination.Target(target))
      Option.when(matches.nonEmpty)(bin -> matches)
    }.toMap)

  /** Top-k re-reads the original row; its omitted mass includes unresolved and external states. */
  def topK(bin: Int, k: Int): Either[TemporalGrid.Refusal, TemporalQuery.Ranked] =
    grid.regions
      .lift(bin)
      .toRight(TemporalGrid.Refusal.InvalidGrid)
      .flatMap(region =>
        grid.prepared.query(region).flatMap(_.topK(k)).left.map(TemporalGrid.Refusal.Query(_))
      )
  override def equals(other: Any): Boolean = other match
    case that: TemporalGridView => digest == that.digest
    case _                      => false
  override def hashCode(): Int = digest.hashCode
  override def toString: String = s"TemporalGridView(${digest.hex})"

object TemporalGridView:
  val Version: String = "temporal-grid-view/v1"
  def from(grid: TemporalGrid): TemporalGridView =
    new TemporalGridView(grid, Checksum.ofText(s"$Version:${grid.digest.hex}"))
