package storymodel4s.codec

import io.circe.Json
import storymodel4s.align.*
import storymodel4s.features.CanonicalDouble

/** Emits a compact Scala/JVM oracle for source-time-grid consumers. */
object SourceTimeGridWitness:
  private val Schema = "storymodel4s/source-time-grid-witness/v2"

  private def decimal(value: Double): Json = Json.fromString(CanonicalDouble.render(value))

  private def region(value: TemporalQuery.Region): Json = Json.obj(
    "intervals" -> Json.fromValues(value.geometry.toVector.flatMap(_.intervals).map(i =>
      Json.obj(
        "start" -> Json.fromString(i.start.toString),
        "endExclusive" -> Json.fromString(i.endExclusive.toString)
      )
    )),
    "points" -> Json.fromValues(
      value.geometry.toVector.flatMap(_.points).map(p => Json.fromString(p.at.toString))
    )
  )

  private def ledger(value: TemporalQuery.Ledger, discrepancy: Double): Json = Json.obj(
    "suppliedTotal" -> decimal(value.suppliedTotal),
    "allocatedSource" -> decimal(value.allocatedSource),
    "supportWithoutAllocation" -> decimal(value.supportWithoutAllocation),
    "unavailableLocation" -> decimal(value.unavailableLocation),
    "rowBudget" -> decimal(value.rowBudget),
    "allocationDiscrepancy" -> decimal(discrepancy),
    "external" -> Json.fromValues(value.external.map { (state, mass) =>
      Json.obj("state" -> Json.fromString(state.toString), "mass" -> decimal(mass))
    })
  )

  private def duration(row: TemporalProjection.Row): Json =
    row.durationWeighted.fold(
      refusal => Json.obj("unavailable" -> Json.fromString(refusal.toString)),
      values => Json.obj("values" -> Json.fromValues(values.toVector.sortBy(_._1).map { (bin, value) =>
        Json.obj("bin" -> Json.fromInt(bin), "quantity" -> decimal(value))
      }))
    )

  private def grid(value: TemporalProjection.Projection, row: TemporalProjection.Row): Json =
    row.grid.fold(
      refusal => Json.obj("unavailable" -> Json.fromString(refusal.toString)),
      checked =>
        Json.obj(
          "perBin" -> Json.fromValues(value.regions.zipWithIndex.map { (r, index) =>
            val (lower, upper) = checked.boundsAt(index).getOrElse(0.0 -> 0.0)
            Json.obj(
              "index" -> Json.fromInt(index),
              "region" -> region(r),
              "allocated" -> decimal(checked.allocatedAt(index).getOrElse(0.0)),
              "lower" -> decimal(lower),
              "upper" -> decimal(upper)
            )
          }),
          "allocatedOutside" -> decimal(checked.allocatedOutside),
          "ledger" -> ledger(checked.prepared.ledger, checked.allocationDiscrepancy)
        )
    )

  private def choose(name: String, value: TemporalProjection.Projection): TemporalProjection.Row =
    if name == "failed-unit-rows" then value.rows.find(_.grid.isLeft).get
    else
      value.rows
        .find(_.grid.toOption.exists(_.allocated.values.exists(_ > 0.0)))
        .orElse(value.rows.find(_.grid.isRight))
        .get

  private def encodedComponents(value: TemporalProjection.Projection, row: TemporalProjection.Row): Json =
    TemporalProjectionCodecs
      .toJson(value)
      .hcursor
      .downField("rows")
      .focus
      .flatMap(_.asArray)
      .flatMap(_.find(_.hcursor.downField("unit").as[String].contains(row.unit.value)))
      .flatMap(_.hcursor.downField("grid").downField("components").focus)
      .getOrElse(Json.arr())

  private def observed(name: String, value: TemporalProjection.Projection): Json =
    val row = choose(name, value)
    Json.obj(
      "id" -> Json.fromString(name),
      "input" -> Json.obj(
        "axis" -> MappingSourceWire.axis(value.coordinate.axis),
        "selection" -> Json.fromString(value.request.selection.toString),
        "bins" -> Json.fromValues(value.regions.map(region)),
        "declarations" -> Json.fromValues(value.request.declarations.map(d =>
          Json.obj(
            "target" -> Json.fromString(d.target.key),
            "assumption" -> Json.fromString(d.assumption.toString),
            "allocation" -> Json.fromString(d.allocation.toString)
          )
        )),
        "rawComponents" -> encodedComponents(value, row)
      ),
      "expected" -> Json.obj(
        "unit" -> Json.fromString(row.unit.value),
        "processing" -> Json.fromString(row.processing.toString),
        "grid" -> grid(value, row),
        "durationWeighted" -> duration(row)
      )
    )

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: SourceTimeGridWitness <reference-sha>")
    println(
      Canonical.print(
        Json.obj(
          "schema" -> Json.fromString(Schema),
          "referenceSha" -> Json.fromString(args.head),
          "cases" -> Json.fromValues(TemporalProjectionFixtures.projections.map(observed))
        )
      )
    )
