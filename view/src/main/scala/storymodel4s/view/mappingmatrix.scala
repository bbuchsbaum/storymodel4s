package storymodel4s.view

import storymodel4s.align.*
import storymodel4s.recall.{InventoryUnit, RecallUnitId}

/** A lossless inspection projection at the mapping's declared target cut. No cell is a new
  * estimate: missing measures stay absent, and posterior fidelity states remain separate. Selection
  * and viewport cropping do not change this matrix or its normalization universe.
  */
final class MappingMatrix private (
    val record: MappingResult,
    val targets: Vector[MappingTarget],
    val externals: Vector[ExternalState],
    val rows: Vector[MappingMatrix.Row]
):
  def row(unit: RecallUnitId): Option[MappingMatrix.Row] = rows.find(_.unit.id == unit)

object MappingMatrix:
  /** The actual checked outcome retains processing/localization status, choice origin, calibration
    * availability, stage provenance and each measure's normalization metadata.
    */
  final class Row private[MappingMatrix] (
      val unit: InventoryUnit,
      val outcome: UnitOutcome,
      val cells: Vector[Cell]
  ):
    def cell(destination: Destination): Option[Cell] = cells.find(_.destination == destination)

  /** A dictionary coordinate can exist without a supplied value. Some(0) is a supplied zero; None
    * is absence. The posterior vector must never be collapsed to one target probability. Raw
    * measure objects preserve channel, scale, direction, stage and derivation identity.
    */
  final class Cell private[MappingMatrix] (
      val destination: Destination,
      val raw: Vector[(RawScores, Double)],
      val normalized: Option[Double],
      val transport: Option[Double],
      val posterior: Vector[(AlignState, Double)],
      val links: Vector[MappingLink],
      val chosen: Boolean
  )

  /** The checked record already proves complete ordinal outcomes and dictionary membership. Keep
    * the entire declared universe; do not invent child scores or aggregate ancestors.
    */
  def from(record: MappingResult): MappingMatrix =
    val targets = record.policies.universe.targets.map(ref => record.source.target(ref).get)
    val externalDestinations = record.outcomes
      .flatMap(outcome =>
        outcome.measures.destinations.toVector ++ outcome.links.map(_.destination) ++
          outcome.decision.toVector.flatMap(_.chosen)
      )
      .collect { case Destination.External(state) => state }
      .distinct
      .sortBy(_.ordinal)
    val columns = targets.map(t => Destination.Target(t.ref)) ++
      externalDestinations.map(Destination.External(_))
    val rows = record.inventory.units.zip(record.outcomes).map { (unit, outcome) =>
      val cells = columns.map { destination =>
        new Cell(
          destination,
          outcome.measures.raw.flatMap(raw => raw.values.get(destination).map(raw -> _)),
          outcome.measures.normalized.flatMap(_.mass.get(destination)),
          outcome.measures.transport.flatMap(_.mass.get(destination)),
          outcome.measures.posterior.toVector.flatMap(
            _.mass.toVector
              .filter((state, _) => Destination.of(state) == destination)
              .sortBy(_._1.key)
          ),
          outcome.links.filter(_.destination == destination),
          outcome.decision.flatMap(_.chosen).contains(destination)
        )
      }
      new Row(unit, outcome, cells)
    }
    new MappingMatrix(record, targets, externalDestinations, rows)
