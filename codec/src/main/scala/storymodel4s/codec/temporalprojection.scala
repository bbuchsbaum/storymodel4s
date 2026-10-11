package storymodel4s.codec

import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.RecallTiming

/** Canonical projection exchange; contextual decode recompiles rather than trusting summaries. */
object TemporalProjectionCodecs:
  val SchemaVersion: String = "temporal-projection/v1"
  enum Error:
    case Invalid, ContextMismatch
    case Compile(reason: TemporalProjection.Refusal)

  private def selection(value: TemporalSupport.Selection): Json = value match
    case TemporalSupport.Selection.Part(bundle) =>
      Json.obj("kind" -> Json.fromString("part"), "bundle" -> Json.fromString(bundle.hex))
    case TemporalSupport.Selection.Occurrence(mapping, occurrence) => Json.obj(
      "kind" -> Json.fromString("occurrence"),
      "mapping" -> Json.fromString(mapping.hex),
      "occurrence" -> Json.fromString(occurrence.value)
    )
  private def rational(value: ExactRational): Json = Json.obj(
    "numerator" -> Json.fromString(value.numerator.toString),
    "denominator" -> Json.fromString(value.denominator.toString)
  )
  private def request(value: TemporalProjection.Request): Json = Json.obj(
    "selection" -> selection(value.selection),
    "declarations" -> Json.fromValues(value.declarations.map(d => Json.obj(
      "target" -> Json.fromString(d.target.key),
      "assumption" -> Json.fromString(d.assumption.toString),
      "allocation" -> Json.fromString(d.allocation.toString)
    ))),
    "bins" -> Json.fromValues(value.bins.map((a, b) => Json.obj(
      "start" -> Json.fromString(a.toString), "endExclusive" -> Json.fromString(b.toString)
    ))),
    "overlap" -> Json.fromString(value.overlap.toString),
    "recallWindow" -> value.recallWindow.fold(Json.Null)(w => Json.obj(
      "clock" -> Json.fromString(w.clock.hex), "start" -> rational(w.start), "endExclusive" -> rational(w.endExclusive)
    ))
  )
  def toJson(value: TemporalProjection.Projection): Json = Json.obj(
    "schemaVersion" -> Json.fromString(SchemaVersion),
    "digest" -> Json.fromString(value.digest.hex),
    "mapping" -> Json.fromString(value.mapping.digest.hex),
    "measure" -> Json.fromString(value.measure.toString),
    "request" -> request(value.request),
    "timingState" -> Json.fromString(value.timingState.toString),
    "timing" -> value.timing.fold(Json.Null)(t => Json.fromString(t.digest.hex)),
    "exposure" -> value.exposure.fold(Json.Null)(e => Json.fromString(e.digest.hex)),
    "rows" -> Json.fromValues(value.rows.map(row => Json.obj(
      "unit" -> Json.fromString(row.unit.value),
      "processing" -> Json.fromString(row.processing.toString),
      "grid" -> row.grid.fold(e => Json.obj("unavailable" -> Json.fromString(e.toString)), g => Json.obj(
        "digest" -> Json.fromString(g.digest.hex),
        "allocated" -> Json.fromValues(g.allocated.toVector.sortBy(_._1).map { (bin, mass) =>
          Json.obj("bin" -> Json.fromString(bin.toString), "mass" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(mass)))
        }),
        "bounds" -> Json.fromValues(g.bounds.toVector.sortBy(_._1).map { (bin, value) =>
          Json.obj("bin" -> Json.fromString(bin.toString), "lower" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(value._1)), "upper" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(value._2)))
        }),
        "allocatedOutside" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(g.allocatedOutside)),
        "ledger" -> Json.obj(
          "supplied" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(g.prepared.ledger.suppliedTotal)),
          "allocatedSource" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(g.prepared.ledger.allocatedSource)),
          "supportWithoutAllocation" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(g.prepared.ledger.supportWithoutAllocation)),
          "unavailableLocation" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(g.prepared.ledger.unavailableLocation)),
          "rowBudget" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(g.prepared.ledger.rowBudget))
        )
      )),
      "exposure" -> row.exposure.fold(Json.Null)(e => Json.obj(
        "numerator" -> Json.fromString(e.duration._1.toString),
        "denominator" -> Json.fromString(e.duration._2.toString),
        "missingWords" -> Json.fromString(e.missingWords.toString),
        "processing" -> Json.fromString(e.processing.toString)
      ))
    )))
  )
  def encode(value: TemporalProjection.Projection): String = Canonical.print(toJson(value))
