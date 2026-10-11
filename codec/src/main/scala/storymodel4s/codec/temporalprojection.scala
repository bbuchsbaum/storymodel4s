package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.RecallTiming

/** Canonical projection exchange; contextual decode recompiles rather than trusting summaries. */
object TemporalProjectionCodecs:
  val SchemaVersion: String = "temporal-projection/v1"
  val RequestSchemaVersion: String = "temporal-request/v1"

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

  private def allocation(value: TemporalQuery.Allocation): Json = value match
    case TemporalQuery.Allocation.SupportOnly => Json.obj("kind" -> Json.fromString("SupportOnly"))
    case TemporalQuery.Allocation.UniformIntervals =>
      Json.obj("kind" -> Json.fromString("UniformIntervals"))
    case TemporalQuery.Allocation.UniformPoints => Json.obj("kind" -> Json.fromString("UniformPoints"))
    case TemporalQuery.Allocation.Mixture(intervalShare) => Json.obj(
      "kind" -> Json.fromString("Mixture"),
      "intervalShare" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(intervalShare))
    )

  private def request(value: TemporalProjection.Request): Json = Json.obj(
    "selection" -> selection(value.selection),
    "declarations" -> Json.fromValues(value.declarations.map(d => Json.obj(
      "target" -> Json.fromString(d.target.key),
      "assumption" -> Json.fromString(d.assumption.toString),
      "allocation" -> allocation(d.allocation)
    ))),
    "bins" -> Json.fromValues(value.bins.map((a, b) => Json.obj(
      "start" -> Json.fromString(a.toString), "endExclusive" -> Json.fromString(b.toString)
    ))),
    "overlap" -> Json.fromString(value.overlap.toString),
    "recallWindow" -> value.recallWindow.fold(Json.Null)(w => Json.obj(
      "clock" -> Json.fromString(w.clock.hex),
      "start" -> rational(w.start),
      "endExclusive" -> rational(w.endExclusive)
    ))
  )

  /** A request is independently portable but its source selection is checked at decode. */
  def encodeRequest(value: TemporalProjection.Request): String = Canonical.print(Json.obj(
    "schemaVersion" -> Json.fromString(RequestSchemaVersion),
    "request" -> request(value)
  ))

  private def axis(value: PresentationAxis): Json = Json.obj(
    "id" -> Json.fromString(value.id.value),
    "fingerprint" -> Json.fromString(value.fingerprint.hex),
    "kind" -> Json.fromString(value.kind.toString),
    "extent" -> Json.fromString(value.extent.toString),
    "timebase" -> value.timebase.fold(Json.Null)(v => Json.fromString(v.toString))
  )
  private def region(value: TemporalQuery.Region): Json = Json.obj(
    "axis" -> Json.fromString(value.axis.id.value),
    "intervals" -> Json.fromValues(value.geometry.toVector.flatMap(_.intervals).map(i => Json.obj(
      "start" -> Json.fromString(i.start.toString), "endExclusive" -> Json.fromString(i.endExclusive.toString)
    ))),
    "points" -> Json.fromValues(value.geometry.toVector.flatMap(_.points).map(p => Json.fromString(p.at.toString)))
  )
  private def fraction(value: (BigInt, BigInt)): Json = Json.obj(
    "numerator" -> Json.fromString(value._1.toString), "denominator" -> Json.fromString(value._2.toString)
  )
  private def quantities(value: Either[TemporalProjection.Refusal, Map[Int, Double]]): Json = value.fold(
    error => Json.obj("unavailable" -> Json.fromString(error.toString)),
    values => Json.obj("values" -> Json.fromValues(values.toVector.sortBy(_._1).map { (bin, quantity) =>
      Json.obj("bin" -> Json.fromString(bin.toString), "quantity" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(quantity)))
    }))
  )
  private def quantityBounds(value: Either[TemporalProjection.Refusal, Map[Int, (Double, Double)]]): Json = value.fold(
    error => Json.obj("unavailable" -> Json.fromString(error.toString)),
    values => Json.obj("values" -> Json.fromValues(values.toVector.sortBy(_._1).map { (bin, bounds) =>
      Json.obj("bin" -> Json.fromString(bin.toString), "lower" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(bounds._1)), "upper" -> Json.fromString(storymodel4s.features.CanonicalDouble.render(bounds._2)))
    }))
  )

  def toJson(value: TemporalProjection.Projection): Json =
    val base = Json.obj(
    "schemaVersion" -> Json.fromString(SchemaVersion),
    "digest" -> Json.fromString(value.digest.hex),
    "mapping" -> Json.fromString(value.mapping.digest.hex),
    "measure" -> Json.fromString(value.measure.toString),
    "request" -> request(value.request),
    "timingState" -> Json.fromString(value.timingState.toString),
    "timing" -> value.timing.fold(Json.Null)(t => Json.fromString(t.digest.hex)),
    "coordinate" -> axis(value.coordinate.axis),
    "regions" -> Json.fromValues(value.regions.map(region)),
    "clockUncertainty" -> Json.fromString(value.clockUncertainty.toString),
    "jointPath" -> Json.fromString(value.jointPath.toString),
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
        "duration" -> fraction(e.duration),
        "onsetEvents" -> Json.fromValues(e.onsetEvents.map(rational)),
        "missingWords" -> Json.fromString(e.missingWords.toString),
        "decomposition" -> Json.fromString(e.decomposition.toString),
        "processing" -> Json.fromString(e.processing.toString)
      )),
      "durationWeighted" -> quantities(row.durationWeighted),
      "durationWeightedBounds" -> quantityBounds(row.durationWeightedBounds)
    )))
    value.exposure.fold(base)(e => base.deepMerge(Json.obj("exposureReadout" -> Json.obj(
      "start" -> rational(e.start),
      "endExclusive" -> rational(e.endExclusive),
      "uncovered" -> fraction(e.uncovered),
      "uncomputedDuration" -> fraction(e.uncomputedDuration),
      "unassignedWords" -> Json.fromValues(e.unassignedWords.map(w => Json.fromString(w.value)))
    ))))

  def encode(value: TemporalProjection.Projection): String = Canonical.print(toJson(value))

  private def decodeRequestJson(value: Json): Either[Error, TemporalProjection.Request] =
    import MappingJson.*
    def decodeAllocation(json: Json): Result[TemporalQuery.Allocation] =
      field[String](json, "kind").flatMap {
        case "SupportOnly"      => Right(TemporalQuery.Allocation.SupportOnly)
        case "UniformIntervals" => Right(TemporalQuery.Allocation.UniformIntervals)
        case "UniformPoints"    => Right(TemporalQuery.Allocation.UniformPoints)
        case "Mixture"          => field[String](json, "intervalShare").flatMap { value =>
          CanonicalPrimitives.parseHexDouble(value)
            .toRight(MappingCodecError.Wire(CodecError.Decode("intervalShare", "invalid binary64")))
            .map(TemporalQuery.Allocation.Mixture(_))
        }
        case _ => invalid("allocation", "unknown allocation")
      }
    def decodeSelection(json: Json): Result[TemporalSupport.Selection] =
      field[String](json, "kind").flatMap {
        case "part" => field[String](json, "bundle").flatMap(s => domain(Checksum.from(s)))
          .map(TemporalSupport.Selection.Part(_))
        case "occurrence" => for
          mapping <- field[String](json, "mapping").flatMap(s => domain(Checksum.from(s)))
          occurrence <- field[String](json, "occurrence").flatMap(s => domain(OccurrenceId.from(s)))
        yield TemporalSupport.Selection.Occurrence(mapping, occurrence)
        case _ => invalid("selection", "unknown selection")
      }
    def decodeRational(json: Json, path: String): Result[ExactRational] = for
      numerator <- field[String](json, "numerator").flatMap(s => scala.util.Try(s.toLong).toOption.toRight(MappingCodecError.Wire(CodecError.Decode(path, "invalid numerator"))))
      denominator <- field[String](json, "denominator").flatMap(s => scala.util.Try(s.toLong).toOption.toRight(MappingCodecError.Wire(CodecError.Decode(path, "invalid denominator"))))
      value <- domain(ExactRational.of(numerator, denominator))
    yield value
    (for
      selectionJson <- field[Json](value, "selection")
      selected <- decodeSelection(selectionJson)
      declarationsJson <- field[Vector[Json]](value, "declarations")
      declarations <- declarationsJson.traverse { declaration => for
        target <- field[String](declaration, "target").flatMap(sourceRef)
        assumptionText <- field[String](declaration, "assumption")
        assumption <- enumValue(assumptionText, TemporalQuery.Assumption.values.toVector, "assumption")
        allocation <- field[Json](declaration, "allocation").flatMap(decodeAllocation)
      yield TemporalQuery.Declaration(target, assumption, allocation) }
      binsJson <- field[Vector[Json]](value, "bins")
      bins <- binsJson.traverse { bin => for
        start <- field[Json](bin, "start").flatMap(decimal(_, "start"))
        end <- field[Json](bin, "endExclusive").flatMap(decimal(_, "endExclusive"))
      yield start -> end }
      overlapText <- field[String](value, "overlap")
      overlap <- enumValue(overlapText, TemporalExposure.Overlap.values.toVector, "overlap")
      windowJson <- field[Json](value, "recallWindow")
      window <- if windowJson.isNull then Right(None) else for
        clock <- field[String](windowJson, "clock").flatMap(s => domain(Checksum.from(s)))
        start <- field[Json](windowJson, "start").flatMap(decodeRational(_, "recallWindow.start"))
        end <- field[Json](windowJson, "endExclusive").flatMap(decodeRational(_, "recallWindow.endExclusive"))
      yield Some(TemporalProjection.RecallWindow(clock, start, end))
    yield TemporalProjection.Request(selected, declarations, bins, overlap, window)).left.map(_ => Error.Invalid)

  /** Decodes the standalone v1 request and validates its selected source coordinates. */
  def decodeRequest(
      text: String,
      source: SourceRepresentation
  ): Either[Error, TemporalProjection.Request] =
    import MappingJson.*
    for
      json <- Canonical.parse(text).left.map(_ => Error.Invalid)
      _ <- uniqueObjectKeys(text).left.map(_ => Error.Invalid)
      schema <- field[String](json, "schemaVersion").left.map(_ => Error.Invalid)
      _ <- Either.cond(schema == RequestSchemaVersion, (), Error.Invalid)
      requestJson <- field[Json](json, "request").left.map(_ => Error.Invalid)
      decoded <- decodeRequestJson(requestJson)
      _ <- TemporalSupport.select(source, decoded.selection).left.map(_ => Error.Invalid)
      _ <- Either.cond(decoded.declarations.forall(d => source.target(d.target).nonEmpty), (), Error.Invalid)
      rebuilt <- Canonical.parse(encodeRequest(decoded)).left.map(_ => Error.Invalid)
      _ <- Either.cond(rebuilt == json, (), Error.ContextMismatch)
    yield decoded

  /** Contextual decode rebuilds every sparse value from the supplied checked mapping and timing. */
  def decode(
      text: String,
      mapping: MappingResult,
      timing: Option[RecallTiming]
  ): Either[Error, TemporalProjection.Projection] =
    import MappingJson.*
    for
      json <- Canonical.parse(text).left.map(_ => Error.Invalid)
      _ <- uniqueObjectKeys(text).left.map(_ => Error.Invalid)
      schema <- field[String](json, "schemaVersion").left.map(_ => Error.Invalid)
      _ <- Either.cond(schema == SchemaVersion, (), Error.Invalid)
      measureText <- field[String](json, "measure").left.map(_ => Error.Invalid)
      measure <- enumValue(measureText, TemporalQuery.Measure.values.toVector, "measure").left.map(_ => Error.Invalid)
      requestJson <- field[Json](json, "request").left.map(_ => Error.Invalid)
      decoded <- decodeRequestJson(requestJson)
      projection <- TemporalProjection.compile(mapping, measure, decoded, timing).left.map(Error.Compile(_))
      _ <- Either.cond(toJson(projection) == json, (), Error.ContextMismatch)
    yield projection
