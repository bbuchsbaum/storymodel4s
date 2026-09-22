package storymodel4s.corpus.intake

import cats.syntax.all.*
import io.circe.{Decoder, Json, Printer}
import io.circe.jawn.JawnParser
import storymodel4s.core.*

/** Versioned declared scanner artifacts. Contextual readers recompute every derived field. */
object ScannerCrosswalkJson:
  import ScannerCrosswalk.*
  val RunSchema: String = "scanner-run/v0.1"
  val LayoutSchema: String = "scanner-layout/v0.1"
  val BindingSchema: String = "scanner-binding/v0.1"
  val Authority: String = "declared-not-independently-verified"
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private val printer = Printer.noSpaces.copy(sortKeys = true, escapeNonAscii = true)
  enum Error:
    case InvalidJson, InvalidRecord, ContextMismatch
    case Rejected(reason: Refusal)
  private type Result[A] = Either[Error, A]
  private def str(s: String): Json = Json.fromString(s)
  private def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  private def array[A](values: Iterable[A])(f: A => Json): Json = Json.fromValues(values.map(f))
  private def tagged(status: String, fields: (String, Json)*): Json = obj(
    ("status" -> str(status)) +: fields*
  )
  private def exact(a: Json, b: Json): Result[Unit] = Either.cond(a == b, (), Error.ContextMismatch)
  private def field[A: Decoder](j: Json, name: String): Result[A] =
    j.hcursor.get[A](name).left.map(_ => Error.InvalidRecord)
  private def hash(j: Json, name: String): Result[Checksum] =
    field[String](j, name).flatMap(s => Checksum.from(s).left.map(_ => Error.InvalidRecord))
  private def checked[A](value: Either[Refusal, A]): Result[A] = value.left.map(Error.Rejected(_))
  private def parse(s: String): Result[Json] = parser.parse(s).left.map(_ => Error.InvalidJson)
  private def rational(v: ExactRational): Json =
    obj("numerator" -> str(v.numerator.toString), "denominator" -> str(v.denominator.toString))
  private def readRational(j: Json): Result[ExactRational] = for
    n <- field[String](j, "numerator").flatMap(_.toLongOption.toRight(Error.InvalidRecord))
    d <- field[String](j, "denominator").flatMap(_.toLongOption.toRight(Error.InvalidRecord))
    value <- ExactRational.of(n, d).left.map(_ => Error.InvalidRecord)
  yield value
  private def optional(value: Option[String]): Json =
    value.fold(tagged("absent"))(s => tagged("present", "value" -> str(s)))
  private def readOptional(j: Json): Result[Option[String]] = field[String](j, "status").flatMap {
    case "absent"  => Right(None)
    case "present" => field[String](j, "value").map(Some(_))
    case _         => Left(Error.InvalidRecord)
  }

  def runDeclaration(run: Run): Json = obj(
    "key" -> obj(
      "dataset" -> str(run.key.dataset),
      "revision" -> str(run.key.revision),
      "participant" -> str(run.key.participant),
      "session" -> optional(run.key.session),
      "task" -> str(run.key.task),
      "run" -> str(run.key.run)
    ),
    "image_sha256" -> str(run.image.hex),
    "header_sha256" -> str(run.header.hex),
    "sample_count" -> Json.fromInt(run.sampleCount),
    "sample_seconds" -> array(run.sampleSeconds)(rational),
    "origin" -> (run.origin match
      case Origin.FirstStoredSample   => tagged("first-stored-sample")
      case Origin.Declared(reference) => tagged("declared", "reference" -> str(reference.hex))),
    "applied_history" -> (run.history match
      case AppliedHistory.Unknown            => tagged("unknown")
      case AppliedHistory.Declared(receipts) =>
        tagged("declared", "receipts" -> array(receipts)(r => str(r.hex))))
  )
  def runToJson(run: Run): Json = obj(
    "schemaVersion" -> str(RunSchema),
    "authority" -> str(Authority),
    "digest" -> str(run.digest.hex),
    "declaration" -> runDeclaration(run)
  )
  def encodeRun(run: Run): String = printer.print(runToJson(run))
  def admitRunDeclaration(j: Json): Result[Run] = for
    k <- field[Json](j, "key")
    dataset <- field[String](k, "dataset")
    revision <- field[String](k, "revision")
    participant <- field[String](k, "participant")
    session <- field[Json](k, "session").flatMap(readOptional)
    task <- field[String](k, "task")
    run <- field[String](k, "run")
    image <- hash(j, "image_sha256")
    header <- hash(j, "header_sha256")
    count <- field[Int](j, "sample_count")
    times <- field[Vector[Json]](j, "sample_seconds").flatMap(_.traverse(readRational))
    o <- field[Json](j, "origin")
    origin <- field[String](o, "status").flatMap {
      case "first-stored-sample" => Right(Origin.FirstStoredSample)
      case "declared"            => hash(o, "reference").map(Origin.Declared(_))
      case _                     => Left(Error.InvalidRecord)
    }
    h <- field[Json](j, "applied_history")
    history <- field[String](h, "status").flatMap {
      case "unknown"  => Right(AppliedHistory.Unknown)
      case "declared" =>
        field[Vector[String]](h, "receipts")
          .flatMap(_.traverse(s => Checksum.from(s).left.map(_ => Error.InvalidRecord)))
          .map(AppliedHistory.Declared(_))
      case _ => Left(Error.InvalidRecord)
    }
    value <- checked(
      Run.declared(
        RunKey(dataset, revision, participant, session, task, run),
        image,
        header,
        count,
        times,
        origin,
        history
      )
    )
    _ <- exact(j, runDeclaration(value))
  yield value
  def decodeRun(text: String): Result[Run] = for
    j <- parse(text)
    run <- field[Json](j, "declaration").flatMap(admitRunDeclaration)
    _ <- exact(j, runToJson(run))
  yield run

  private def slot(s: Slot): Json = s match
    case Slot.Padding(reason)            => tagged("padding", "reason" -> str(reason))
    case Slot.Acquired(index, censoring) =>
      tagged(
        "acquired",
        "stored_index" -> Json.fromInt(index),
        "censoring" -> (censoring match
          case Censoring.Included           => tagged("included")
          case Censoring.Unknown            => tagged("unknown")
          case Censoring.Censored(evidence) => tagged("censored", "evidence" -> str(evidence.hex)))
      )
  private def readSlot(j: Json): Result[Slot] = field[String](j, "status").flatMap {
    case "padding"  => field[String](j, "reason").map(Slot.Padding(_))
    case "acquired" =>
      for
        index <- field[Int](j, "stored_index")
        c <- field[Json](j, "censoring")
        censoring <- field[String](c, "status").flatMap {
          case "included" => Right(Censoring.Included)
          case "unknown"  => Right(Censoring.Unknown)
          case "censored" => hash(c, "evidence").map(Censoring.Censored(_))
          case _          => Left(Error.InvalidRecord)
        }
      yield Slot.Acquired(index, censoring)
    case _ => Left(Error.InvalidRecord)
  }
  def layoutDeclaration(layout: Layout): Json = obj(
    "run_digest" -> str(layout.run.digest.hex),
    "analysis_array_sha256" -> str(layout.analysisArray.hex),
    "receipt" -> str(layout.receipt.hex),
    "length" -> Json.fromInt(layout.slots.size),
    "slots" -> array(layout.slots)(slot)
  )
  def layoutToJson(layout: Layout): Json = obj(
    "schemaVersion" -> str(LayoutSchema),
    "authority" -> str(Authority),
    "digest" -> str(layout.digest.hex),
    "declaration" -> layoutDeclaration(layout),
    "dropped_stored_indices" -> array(layout.droppedStoredIndices)(Json.fromInt),
    "samples" -> array(layout.slots.indices) { i =>
      obj(
        "analysis_index" -> Json.fromInt(i),
        "slot" -> slot(layout.slots(i)),
        "time" -> layout
          .sample(i)
          .toOption
          .get
          .fold(tagged("unavailable-padding"))(t =>
            tagged("acquired", "seconds" -> rational(t.seconds))
          )
      )
    }
  )
  def encodeLayout(layout: Layout): String = printer.print(layoutToJson(layout))
  def admitLayoutDeclaration(j: Json, run: Run): Result[Layout] = for
    r <- hash(j, "run_digest")
    _ <- Either.cond(r == run.digest, (), Error.ContextMismatch)
    array <- hash(j, "analysis_array_sha256")
    receipt <- hash(j, "receipt")
    length <- field[Int](j, "length")
    slots <- field[Vector[Json]](j, "slots").flatMap(_.traverse(readSlot))
    value <- checked(Layout.declared(run, array, length, slots, receipt))
    _ <- exact(j, layoutDeclaration(value))
  yield value
  def decodeLayout(text: String, run: Run): Result[Layout] = for
    j <- parse(text)
    value <- field[Json](j, "declaration").flatMap(admitLayoutDeclaration(_, run))
    _ <- exact(j, layoutToJson(value))
  yield value

  def bindingToJson(binding: Binding): Json = obj(
    "schemaVersion" -> str(BindingSchema),
    "authority" -> str(Authority),
    "digest" -> str(binding.digest.hex),
    "reference_digest" -> str(binding.reference.digest.hex),
    "reference_kind" -> str(binding.reference.kind.toString),
    "reference_artifact" -> str(binding.reference.artifactIdentity.hex),
    "run_digest" -> str(binding.run.digest.hex),
    "coordinate_units" -> str("seconds-to-seconds"),
    "domain" -> obj(
      "start" -> rational(binding.domain.start),
      "end_exclusive" -> rational(binding.domain.endExclusive)
    ),
    "scale" -> rational(binding.repair.scale),
    "offset" -> rational(binding.repair.offset),
    "repair_identity" -> str(binding.repair.identity.hex),
    "evidence" -> obj(
      "reference" -> str(binding.evidence.reference.hex),
      "run" -> str(binding.evidence.run.hex),
      "declaration" -> str(binding.evidence.declaration.hex),
      "supporting_artifacts" -> array(binding.evidence.supportingArtifacts)(h => str(h.hex))
    )
  )
  def encodeBinding(binding: Binding): String = printer.print(bindingToJson(binding))
  def decodeBinding(text: String, reference: Reference, run: Run): Result[Binding] = for
    j <- parse(text)
    d <- field[Json](j, "domain")
    start <- field[Json](d, "start").flatMap(readRational)
    end <- field[Json](d, "end_exclusive").flatMap(readRational)
    window <- checked(Window.of(start, end))
    scale <- field[Json](j, "scale").flatMap(readRational)
    offset <- field[Json](j, "offset").flatMap(readRational)
    e <- field[Json](j, "evidence")
    ref <- hash(e, "reference")
    r <- hash(e, "run")
    declaration <- hash(e, "declaration")
    artifacts <- field[Vector[String]](e, "supporting_artifacts")
      .flatMap(_.traverse(s => Checksum.from(s).left.map(_ => Error.InvalidRecord)))
    value <- checked(
      Binding.declared(
        reference,
        run,
        window,
        scale,
        offset,
        Evidence(ref, r, declaration, artifacts)
      )
    )
    _ <- exact(j, bindingToJson(value))
  yield value
