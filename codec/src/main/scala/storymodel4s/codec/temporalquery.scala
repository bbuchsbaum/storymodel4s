package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId

/** Canonical, contextual readouts. Decode re-executes the checked query, never trusts wire mass. */
object TemporalQueryCodecs:
  import MappingJson.*
  import CanonicalPrimitives.given
  import TemporalQuery.*
  val SchemaVersion: String = "temporal-query/v0.1"

  private def allocation(value: Allocation): Json = value match
    case Allocation.Mixture(share) => tagged("Mixture", "interval_share" -> number(share))
    case other                     => tagged(other.toString)
  private def declaration(d: Declaration): Json = obj(
    "target" -> str(d.target.key),
    "assumption" -> str(d.assumption.toString),
    "allocation" -> allocation(d.allocation)
  )
  private def selection(s: TemporalSupport.Selection): Json = s match
    case TemporalSupport.Selection.Part(bundle) => tagged("part", "bundle" -> str(bundle.hex))
    case TemporalSupport.Selection.Occurrence(mapping, occurrence) =>
      tagged("occurrence", "mapping" -> str(mapping.hex), "occurrence" -> str(occurrence.value))
  private def bounds(b: Bounds): Json = obj("lower" -> number(b.lower), "upper" -> number(b.upper))
  private def geometry(g: Option[PlaybackSupport]): Json = obj(
    "intervals" -> array(
      g.toVector
        .flatMap(_.intervals)
        .map(i => obj("start_tick" -> long(i.start), "end_exclusive_tick" -> long(i.endExclusive)))
    ),
    "points" -> array(g.toVector.flatMap(_.points).map(p => long(p.at)))
  )
  def toJson(value: Readout): Json =
    val p = value.prepared
    val ledger = p.ledger
    obj(
      "schemaVersion" -> str(SchemaVersion),
      "derivation" -> str(Version),
      "digest" -> str(value.digest.hex),
      "prepared_digest" -> str(p.digest.hex),
      "mapping_digest" -> str(p.mapping.digest.hex),
      "source_digest" -> str(p.mapping.source.digest.hex),
      "inventory_digest" -> str(p.mapping.inventory.digest.hex),
      "inputs" -> obj(
        "unit" -> str(p.unit.value),
        "measure" -> str(p.measure.toString),
        "selection" -> selection(p.coordinate.selection),
        "declarations" -> array(p.domains.map(d => declaration(d.declaration)))
      ),
      "measure_stage" -> str(p.stage.digest.hex),
      "mapping_policies" -> MappingRecordWire.policies(p.mapping.policies),
      "axis" -> MappingSourceWire.axis(p.coordinate.axis),
      "region" -> geometry(value.region.geometry),
      "domains" -> array(
        p.domains.map(d =>
          obj(
            "declaration" -> declaration(d.declaration),
            "support" -> TemporalSupportCodecs.toJson(d.support),
            "availability" -> d.availability.fold(
              r => tagged("unavailable", "reason" -> str(r.toString)),
              _ => tagged("conditional-on-declared-support")
            ),
            "allocation" -> d.allocation
              .fold(r => tagged("unavailable", "reason" -> str(r.toString)), allocation)
          )
        )
      ),
      "contributions" -> array(value.contributions.map { c =>
        val alternative = c.component.alternative match
          case Alternative.Score(destination) =>
            tagged("score", "destination" -> str(destination.key))
          case Alternative.Posterior(state) =>
            tagged("posterior-state", "state" -> strings(AlignState.keyParts(state)))
        val location = c.component.location match
          case Location.External(state)    => tagged("external", "state" -> str(state.toString))
          case Location.Unresolved(reason) =>
            tagged("unavailable", "reason" -> str(reason.toString))
          case Location.Resolved(d) =>
            tagged("conditional-on-declared-support", "target" -> str(d.declaration.target.key))
        obj(
          "alternative" -> alternative,
          "weight" -> number(c.component.weight),
          "location" -> location,
          "resolved_support_bounds" -> optional(c.resolvedBounds)(bounds),
          "allocated_region_mass" -> optional(c.allocated)(number)
        )
      }),
      "resolved_support_bounds" -> bounds(value.resolvedBounds),
      "allocated_region_mass" -> number(value.allocatedRegionMass),
      "ledger" -> obj(
        "supplied_total" -> number(ledger.suppliedTotal),
        "row_sum_discrepancy" -> number(ledger.rowSumDiscrepancy),
        "allocated_source" -> number(ledger.allocatedSource),
        "support_without_allocation" -> number(ledger.supportWithoutAllocation),
        "unavailable_location" -> number(ledger.unavailableLocation),
        "external" -> array(
          ledger.external.map((s, w) => obj("state" -> str(s.toString), "mass" -> number(w)))
        )
      )
    )
  def encode(value: Readout): String = print(toJson(value))

  private def readAllocation(j: Json): Result[Allocation] = status(j).flatMap {
    case "SupportOnly"      => Right(Allocation.SupportOnly)
    case "UniformIntervals" => Right(Allocation.UniformIntervals)
    case "UniformPoints"    => Right(Allocation.UniformPoints)
    case "Mixture"          =>
      field[Double](j, "interval_share")
        .map(Allocation.Mixture(_))
    case _ => invalid("allocation", "unsupported allocation")
  }
  private def readSelection(j: Json): Result[TemporalSupport.Selection] = status(j).flatMap {
    case "part" =>
      field[String](j, "bundle")
        .flatMap(s => domain(Checksum.from(s)))
        .map(TemporalSupport.Selection.Part(_))
    case "occurrence" =>
      for
        mapping <- field[String](j, "mapping").flatMap(s => domain(Checksum.from(s)))
        occurrence <- field[String](j, "occurrence").flatMap(s => domain(OccurrenceId.from(s)))
      yield TemporalSupport.Selection.Occurrence(mapping, occurrence)
    case _ => invalid("selection", "unsupported selection")
  }
  private def queryResult[A](value: Either[Refusal, A]): Result[A] =
    value.left.map(e => MappingCodecError.Wire(CodecError.Decode("temporal-query", e.toString)))

  def decode(text: String, mapping: MappingResult): Result[Readout] =
    for
      json <- Canonical.parse(text).left.map(MappingCodecError.Wire(_))
      _ <- uniqueObjectKeys(text)
      inputs <- field[Json](json, "inputs")
      unit <- field[String](inputs, "unit").flatMap(s => domain(RecallUnitId.from(s)))
      measure <- field[String](inputs, "measure").flatMap(s =>
        enumValue(s, Measure.values.toVector, "measure")
      )
      selected <- field[Json](inputs, "selection").flatMap(readSelection)
      ds <- field[Vector[Json]](inputs, "declarations").flatMap(_.traverse { d =>
        for
          ref <- field[String](d, "target").flatMap(sourceRef)
          assumption <- field[String](d, "assumption").flatMap(s =>
            enumValue(s, Assumption.values.toVector, "assumption")
          )
          a <- field[Json](d, "allocation").flatMap(readAllocation)
        yield Declaration(ref, assumption, a)
      })
      prepared <- queryResult(prepare(mapping, unit, measure, selected, ds))
      region <- field[Json](json, "region")
      intervals <- field[Vector[Json]](region, "intervals").flatMap(_.traverse { i =>
        for
          start <- field[Json](i, "start_tick").flatMap(decimal(_, "start_tick"))
          end <- field[Json](i, "end_exclusive_tick").flatMap(decimal(_, "end_exclusive_tick"))
        yield start -> end
      })
      points <- field[Vector[Json]](region, "points").flatMap(_.traverse(decimal(_, "point")))
      checkedRegion <- queryResult(Region.on(prepared.coordinate.axis, intervals, points))
      result <- queryResult(prepared.query(checkedRegion))
      _ <- exact(json, toJson(result), "canonical-record")
    yield result
