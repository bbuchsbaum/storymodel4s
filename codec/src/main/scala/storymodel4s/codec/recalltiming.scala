package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import storymodel4s.core.{Checksum, DomainError, ExactRational}
import storymodel4s.recall.*
import storymodel4s.recall.RecallTiming.*

enum RecallTimingCodecError:
  case Wire(error: CodecError)
  case Rejected(error: Refusal)
  case Mismatch(field: String)

/** Inventory-contextual timing interchange. Decoding checks declarations, never certifies them. */
object RecallTimingCodecs:
  val SchemaVersion: String = "recall-timing/v0.1"
  private type Result[A] = Either[RecallTimingCodecError, A]
  private val str = Json.fromString
  private def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  private def tag(status: String, fields: (String, Json)*): Json =
    obj(("status" -> str(status)) +: fields*)
  private def hash(value: Checksum): Json = str(value.hex)
  private def seconds(value: ExactRational): Json = obj(
    "numerator" -> str(value.numerator.toString),
    "denominator" -> str(value.denominator.toString)
  )
  private def basis(value: Basis): Json = value match
    case Basis.SourceReported(evidence) => tag("source-reported", "evidence" -> hash(evidence))
    case Basis.Estimated(recipe)        => tag("estimated", "recipe" -> hash(recipe))
  private def observation(value: Observation): Json = value match
    case Observation.Missing(reason)  => tag("missing", "reason" -> str(reason.toString))
    case Observation.OnsetOnly(at, b) =>
      tag("onset-only", "seconds" -> seconds(at), "basis" -> basis(b))
    case Observation.Interval(start, end, b) =>
      tag(
        "interval",
        "start_seconds" -> seconds(start),
        "end_exclusive_seconds" -> seconds(end),
        "basis" -> basis(b)
      )
  private def clock(value: Clock): Json = obj(
    "artifact" -> hash(value.artifact),
    "key" -> str(value.key.value),
    "descriptor" -> hash(value.descriptor),
    "unit" -> str("seconds"),
    "recording" -> (value.recording match
      case RecordingIdentity.Unestablished => tag("unestablished")
      case RecordingIdentity.Declared(id)  => tag("declared", "identity" -> hash(id))),
    "origin" -> (value.origin match
      case Origin.Unestablished       => tag("unestablished")
      case Origin.RecordingStart      => tag("recording-start")
      case Origin.Declared(reference) => tag("declared", "reference" -> hash(reference))),
    "digest" -> hash(value.digest)
  )
  private def optional[A](value: Option[A])(f: A => Json): Json = value match
    case None    => tag("absent")
    case Some(v) => tag("present", "value" -> f(v))
  private def witness(value: Witness): Json =
    obj("word" -> str(value.word.value), "seconds" -> seconds(value.seconds))
  private def summary(value: UnitSummary): Json = obj(
    "unit" -> str(value.unit.value),
    "clock" -> hash(value.clock.digest),
    "total_words" -> Json.fromInt(value.totalWords),
    "available_onsets" -> Json.fromInt(value.availableOnsets),
    "supplied_intervals" -> Json.fromInt(value.suppliedIntervals),
    "estimated_words" -> Json.fromInt(value.estimatedWords),
    "first_member" -> optional(value.firstMember)(e => str(e.word.value)),
    "last_member" -> optional(value.lastMember)(e => str(e.word.value)),
    "first_available_onset" -> optional(value.firstAvailableOnset)(witness),
    "last_available_onset" -> optional(value.lastAvailableOnset)(witness),
    "comparable_adjacent_pairs" -> Json.fromInt(value.comparableAdjacentPairs),
    "unobserved_adjacent_pairs" -> Json.fromInt(value.unobservedAdjacentPairs),
    "backward_pairs" -> Json.fromValues(
      value.backwardPairs.map(p => obj("from" -> str(p.from.value), "to" -> str(p.to.value)))
    ),
    "equal_adjacent_pairs" -> Json.fromInt(value.equalAdjacentPairs)
  )
  def toJson(value: RecallTiming): Json = obj(
    "schemaVersion" -> str(SchemaVersion),
    "inventory_digest" -> hash(value.inventory.digest),
    "clock" -> clock(value.clock),
    "provenance" -> obj(
      "correspondence" -> hash(value.provenance.correspondence),
      "recording_link" -> (value.provenance.recordingLink match
        case RecordingLink.NotEstablished    => tag("unestablished")
        case RecordingLink.Declared(receipt) => tag("declared", "receipt" -> hash(receipt)))
    ),
    "entries" -> Json.fromValues(
      value.entries.map(e =>
        obj(
          "word" -> str(e.word.value),
          "clock" -> hash(e.clock.digest),
          "observation" -> observation(e.observation)
        )
      )
    ),
    "unit_onset_diagnostics" -> Json.fromValues(value.unitSummaries.map(summary)),
    "digest" -> hash(value.digest)
  )
  def encode(value: RecallTiming): String = MappingJson.print(toJson(value))

  /** A timing file alone cannot authenticate its inventory or establish scanner alignment. */
  def decode(text: String, inventory: RecallInventory): Result[RecallTiming] =
    for
      json <- Canonical.parse(text).left.map(RecallTimingCodecError.Wire(_))
      _ <- MappingJson
        .uniqueObjectKeys(text)
        .left
        .map(e => RecallTimingCodecError.Wire(CodecError.Decode("$", e.message)))
      version <- field[String](json, "schemaVersion")
      _ <- requireEqual(version, SchemaVersion, "schemaVersion")
      inventoryDigest <- field[String](json, "inventory_digest")
      _ <- requireEqual(inventoryDigest, inventory.digest.hex, "inventory_digest")
      c <- field[Json](json, "clock").flatMap(readClock)
      p <- field[Json](json, "provenance").flatMap(readProvenance)
      entries <- field[Vector[Json]](json, "entries").flatMap(_.traverse(readEntry(_, c)))
      result <- RecallTiming
        .checked(inventory, c, entries, p)
        .left
        .map(RecallTimingCodecError.Rejected(_))
      digest <- field[String](json, "digest")
      _ <- requireEqual(digest, result.digest.hex, "digest")
      _ <- requireEqual(json, toJson(result), "canonical-record")
    yield result

  private def field[A: Decoder](json: Json, name: String): Result[A] =
    json.hcursor
      .get[A](name)
      .left
      .map(e => RecallTimingCodecError.Wire(CodecError.Decode(name, e.message)))
  private def domain[A](value: Either[DomainError, A]): Result[A] =
    value.left.map(e => RecallTimingCodecError.Wire(CodecError.Domain(e)))
  private def invalid[A](field: String): Result[A] = Left(RecallTimingCodecError.Mismatch(field))
  private def requireEqual[A](actual: A, expected: A, name: String): Result[Unit] =
    Either.cond(actual == expected, (), RecallTimingCodecError.Mismatch(name))
  private def readHash(json: Json, name: String): Result[Checksum] =
    field[String](json, name).flatMap(s => domain(Checksum.from(s)))
  private def status(json: Json): Result[String] = field[String](json, "status")
  private def readLong(json: Json, name: String): Result[Long] =
    field[String](json, name).flatMap { s =>
      s.toLongOption.filter(_.toString == s).toRight(RecallTimingCodecError.Mismatch(name))
    }
  private def readSeconds(json: Json): Result[ExactRational] =
    for
      n <- readLong(json, "numerator")
      d <- readLong(json, "denominator")
      v <- domain(ExactRational.of(n, d))
      _ <- requireEqual(json, seconds(v), "seconds")
    yield v
  private def readBasis(json: Json): Result[Basis] = status(json).flatMap {
    case "source-reported" => readHash(json, "evidence").map(Basis.SourceReported(_))
    case "estimated"       => readHash(json, "recipe").map(Basis.Estimated(_))
    case _                 => invalid("basis")
  }
  private def readObservation(json: Json): Result[Observation] = status(json).flatMap {
    case "missing" =>
      field[String](json, "reason").flatMap(r =>
        MissingReason.values
          .find(_.toString == r)
          .map(Observation.Missing(_))
          .toRight(RecallTimingCodecError.Mismatch("missing-reason"))
      )
    case "onset-only" =>
      for
        s <- field[Json](json, "seconds").flatMap(readSeconds)
        b <- field[Json](json, "basis").flatMap(readBasis)
      yield Observation.OnsetOnly(s, b)
    case "interval" =>
      for
        s <- field[Json](json, "start_seconds").flatMap(readSeconds)
        e <- field[Json](json, "end_exclusive_seconds").flatMap(readSeconds)
        b <- field[Json](json, "basis").flatMap(readBasis)
      yield Observation.Interval(s, e, b)
    case _ => invalid("observation")
  }
  private def readRecording(json: Json): Result[RecordingIdentity] = status(json).flatMap {
    case "unestablished" => Right(RecordingIdentity.Unestablished)
    case "declared"      => readHash(json, "identity").map(RecordingIdentity.Declared(_))
    case _               => invalid("recording")
  }
  private def readOrigin(json: Json): Result[Origin] = status(json).flatMap {
    case "unestablished"   => Right(Origin.Unestablished)
    case "recording-start" => Right(Origin.RecordingStart)
    case "declared"        => readHash(json, "reference").map(Origin.Declared(_))
    case _                 => invalid("origin")
  }
  private def readClock(json: Json): Result[Clock] = for
    artifact <- readHash(json, "artifact")
    key <- field[String](json, "key").flatMap(s => domain(ClockKey.from(s)))
    descriptor <- readHash(json, "descriptor")
    recording <- field[Json](json, "recording").flatMap(readRecording)
    origin <- field[Json](json, "origin").flatMap(readOrigin)
    c = Clock.declared(artifact, key, descriptor, recording, origin)
    _ <- requireEqual(json, clock(c), "clock")
  yield c
  private def readProvenance(json: Json): Result[Provenance] = for
    correspondence <- readHash(json, "correspondence")
    link <- field[Json](json, "recording_link")
    l <- status(link).flatMap {
      case "unestablished" => Right(RecordingLink.NotEstablished)
      case "declared"      => readHash(link, "receipt").map(RecordingLink.Declared(_))
      case _               => invalid("recording_link")
    }
  yield Provenance(correspondence, l)
  private def readEntry(json: Json, c: Clock): Result[Entry] = for
    word <- field[String](json, "word").flatMap(s => domain(RecallWordId.from(s)))
    clockDigest <- field[String](json, "clock")
    _ <- requireEqual(clockDigest, c.digest.hex, "entry-clock")
    o <- field[Json](json, "observation").flatMap(readObservation)
  yield Entry(word, c, o)
