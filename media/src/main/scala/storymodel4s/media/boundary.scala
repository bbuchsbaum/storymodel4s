package storymodel4s.media

import io.circe.{ACursor, Json}
import storymodel4s.core.{
  BoundaryId,
  BoundaryLayer,
  BoundarySearchCoverage,
  Checksum,
  ContentAddress,
  DomainError,
  EditionId,
  ObservationAuthority,
  PlaybackInstant,
  PlaybackInterval,
  PresentationAxis,
  SourceBundle,
  SourceDerivationReceipt
}

enum FlashFilterMode:
  case Merge, Suppress

/** Every ContentDetector parameter, declared. `kernelSize` of `None` asks the library to resolve it
  * from the frame size; the resolved value must then come back in the outcome or the join refuses,
  * because no library default may stand in for a recorded value (ledger §5). `frameCounterFps` is
  * the rate of the counter the detector API demands; it is not evidence.
  */
final case class ContentDetectorRecipe(
    threshold: Double,
    minSceneLen: Int,
    deltaHue: Double,
    deltaSat: Double,
    deltaLum: Double,
    deltaEdges: Double,
    lumaOnly: Boolean,
    kernelSize: Option[Int],
    filterMode: FlashFilterMode,
    frameCounterFps: Double
):
  def identity: Checksum =
    ContentAddress.digest(
      Vector(
        "content-detector-recipe",
        threshold.toString,
        minSceneLen.toString,
        deltaHue.toString,
        deltaSat.toString,
        deltaLum.toString,
        deltaEdges.toString,
        lumaOnly.toString,
        kernelSize.fold("auto")(_.toString),
        filterMode.toString,
        frameCounterFps.toString
      )
    )

  def json: Json =
    Json.obj(
      "type" -> Json.fromString("ContentDetector"),
      "threshold" -> Json.fromDoubleOrNull(threshold),
      "minSceneLen" -> Json.fromInt(minSceneLen),
      "weights" -> Json.obj(
        "deltaHue" -> Json.fromDoubleOrNull(deltaHue),
        "deltaSat" -> Json.fromDoubleOrNull(deltaSat),
        "deltaLum" -> Json.fromDoubleOrNull(deltaLum),
        "deltaEdges" -> Json.fromDoubleOrNull(deltaEdges)
      ),
      "lumaOnly" -> Json.fromBoolean(lumaOnly),
      "kernelSize" -> kernelSize.fold(Json.Null)(Json.fromInt),
      "filterMode" -> Json.fromString(filterMode match
        case FlashFilterMode.Merge    => "MERGE"
        case FlashFilterMode.Suppress => "SUPPRESS"),
      "frameCounterFps" -> Json.fromDoubleOrNull(frameCounterFps)
    )

object ContentDetectorRecipe:
  def parse(c: ACursor): Either[DomainError, ContentDetectorRecipe] =
    for
      tpe <- MediaJson.string(c.downField("type"), "detector.type")
      _ <-
        if tpe == "ContentDetector" then Right(())
        else
          Left(DomainError.InvalidFormat("detector.type", tpe, "only ContentDetector is admitted"))
      threshold <- MediaJson.double(c.downField("threshold"), "detector.threshold")
      minSceneLen <- MediaJson.int(c.downField("minSceneLen"), "detector.minSceneLen")
      w = c.downField("weights")
      hue <- MediaJson.double(w.downField("deltaHue"), "detector.weights.deltaHue")
      sat <- MediaJson.double(w.downField("deltaSat"), "detector.weights.deltaSat")
      lum <- MediaJson.double(w.downField("deltaLum"), "detector.weights.deltaLum")
      edges <- MediaJson.double(w.downField("deltaEdges"), "detector.weights.deltaEdges")
      lumaOnly <- MediaJson.boolean(c.downField("lumaOnly"), "detector.lumaOnly")
      kernel <- MediaJson.optionalInt(c.downField("kernelSize"), "detector.kernelSize")
      mode <- MediaJson.string(c.downField("filterMode"), "detector.filterMode").flatMap {
        case "MERGE"    => Right(FlashFilterMode.Merge)
        case "SUPPRESS" => Right(FlashFilterMode.Suppress)
        case other      =>
          Left(DomainError.InvalidFormat("detector.filterMode", other, "MERGE or SUPPRESS"))
      }
      fps <- MediaJson.double(c.downField("frameCounterFps"), "detector.frameCounterFps")
    yield ContentDetectorRecipe(
      threshold,
      minSceneLen,
      hue,
      sat,
      lum,
      edges,
      lumaOnly,
      kernel,
      mode,
      fps
    )

/** The request the adapter issues to the worker: which frames (by identity, count, and geometry)
  * and which recipe. The worker echoes all of it back and the join checks the echo.
  */
final case class DetectorRequest(
    requestId: String,
    framesSha256: Checksum,
    count: Int,
    geometry: PictureGeometry,
    recipe: ContentDetectorRecipe
):
  /** Wire form for the worker; `frameFile` is the local path the worker reads. */
  def render(frameFile: String): String =
    Json
      .obj(
        "schema" -> Json.fromString(DetectorRequest.Schema),
        "schemaVersion" -> Json.fromInt(1),
        "requestId" -> Json.fromString(requestId),
        "frames" -> Json.obj(
          "file" -> Json.fromString(frameFile),
          "sha256" -> Json.fromString(framesSha256.hex),
          "count" -> Json.fromInt(count),
          "width" -> Json.fromInt(geometry.width),
          "height" -> Json.fromInt(geometry.height),
          "pixelFormat" -> Json.fromString(Ffmpeg.PixelFormat)
        ),
        "detector" -> recipe.json
      )
      .spaces2

object DetectorRequest:
  val Schema: String = "storymodel4s.media.detector-request"

  def issue(frames: FrameSet, recipe: ContentDetectorRecipe, requestId: String): DetectorRequest =
    DetectorRequest(requestId, frames.framesSha256, frames.count, frames.geometry, recipe)

  def parse(text: String): Either[DomainError, DetectorRequest] =
    for
      json <- MediaJson.parseDocument("detector-request", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      requestId <- MediaJson.string(c.downField("requestId"), "requestId")
      f = c.downField("frames")
      sha <- MediaJson.string(f.downField("sha256"), "frames.sha256").flatMap(Checksum.from)
      count <- MediaJson.int(f.downField("count"), "frames.count")
      width <- MediaJson.int(f.downField("width"), "frames.width")
      height <- MediaJson.int(f.downField("height"), "frames.height")
      pix <- MediaJson.string(f.downField("pixelFormat"), "frames.pixelFormat")
      _ <-
        if pix == Ffmpeg.PixelFormat then Right(())
        else
          Left(
            DomainError.InvalidFormat("frames.pixelFormat", pix, s"expected ${Ffmpeg.PixelFormat}")
          )
      recipe <- ContentDetectorRecipe.parse(c.downField("detector"))
    yield DetectorRequest(requestId, sha, count, PictureGeometry(width, height), recipe)

/** Recorded worker run: the worker realization (script digest and runtime versions), the request it
  * answered, and the digest of the outcome it wrote.
  */
final case class DetectorEnvelope(
    fixtureId: String,
    worker: ToolRealization,
    requestFile: String,
    outcomeFile: String,
    outcomeSha256: Checksum
):
  def verifyOutcome(bytes: Array[Byte]): Either[DomainError, String] =
    val observed = Checksum.ofBytes(bytes)
    if observed != outcomeSha256 then
      Left(
        DomainError.InvariantViolation(
          "envelope/outcome",
          s"$fixtureId: recorded outcome hashes to ${observed.short()}, envelope declares ${outcomeSha256.short()}"
        )
      )
    else Right(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))

object DetectorEnvelope:
  val Schema: String = "storymodel4s.media.detector-envelope"

  def parse(text: String): Either[DomainError, DetectorEnvelope] =
    for
      json <- MediaJson.parseDocument("detector-envelope", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      fixtureId <- MediaJson.string(c.downField("fixtureId"), "fixtureId")
      w = c.downField("worker")
      name <- MediaJson.string(w.downField("name"), "worker.name")
      version <- MediaJson.string(w.downField("versionLine"), "worker.versionLine")
      script <- MediaJson
        .string(w.downField("scriptSha256"), "worker.scriptSha256")
        .flatMap(Checksum.from)
      worker <- ToolRealization.of(name, version, script)
      requestFile <- MediaJson.string(c.downField("requestFile"), "requestFile")
      outcomeFile <- MediaJson.string(c.downField("outcomeFile"), "outcomeFile")
      outcomeSha <- MediaJson
        .string(c.downField("outcomeSha256"), "outcomeSha256")
        .flatMap(Checksum.from)
    yield DetectorEnvelope(fixtureId, worker, requestFile, outcomeFile, outcomeSha)

final case class FrameMetrics(ordinal: Int, values: Map[String, Double])

/** What the worker returned, parsed and nothing more. Believing any of it is the join's job. */
final case class DetectorOutcome(
    requestId: String,
    runtime: Map[String, String],
    framesSha256: Checksum,
    framesCount: Int,
    geometry: PictureGeometry,
    pixelFormat: String,
    recipe: ContentDetectorRecipe,
    resolvedKernelSize: Option[Int],
    metricKeys: Vector[String],
    metrics: Vector[FrameMetrics],
    cuts: Vector[Int],
    coverageRequested: Int,
    coverageObserved: Int
)

object DetectorOutcome:
  val Schema: String = "storymodel4s.media.detector-outcome"
  val ScoreKey: String = "content_val"

  def parse(text: String): Either[DomainError, DetectorOutcome] =
    for
      json <- MediaJson.parseDocument("detector-outcome", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      requestId <- MediaJson.string(c.downField("requestId"), "requestId")
      runtime <- MediaJson.stringMap(c.downField("runtime"), "runtime")
      f = c.downField("frames")
      sha <- MediaJson.string(f.downField("sha256"), "frames.sha256").flatMap(Checksum.from)
      count <- MediaJson.int(f.downField("count"), "frames.count")
      width <- MediaJson.int(f.downField("width"), "frames.width")
      height <- MediaJson.int(f.downField("height"), "frames.height")
      pix <- MediaJson.string(f.downField("pixelFormat"), "frames.pixelFormat")
      d = c.downField("detector")
      recipe <- ContentDetectorRecipe.parse(d)
      resolved <- MediaJson.optionalInt(
        d.downField("resolvedKernelSize"),
        "detector.resolvedKernelSize"
      )
      keysJson <- MediaJson.array(c.downField("metricKeys"), "metricKeys")
      keys <- MediaJson.traverse(keysJson)(j => MediaJson.string(j.hcursor, "metricKeys[]"))
      rowsJson <- MediaJson.array(c.downField("metrics"), "metrics")
      rows <- MediaJson.traverse(rowsJson)(j => parseRow(j, keys))
      cutsJson <- MediaJson.array(c.downField("cuts"), "cuts")
      cuts <- MediaJson.traverse(cutsJson)(j => MediaJson.int(j.hcursor, "cuts[]"))
      cov = c.downField("coverage")
      requested <- MediaJson.int(cov.downField("requested"), "coverage.requested")
      observed <- MediaJson.int(cov.downField("observed"), "coverage.observed")
    yield DetectorOutcome(
      requestId,
      runtime,
      sha,
      count,
      PictureGeometry(width, height),
      pix,
      recipe,
      resolved,
      keys,
      rows,
      cuts,
      requested,
      observed
    )

  private def parseRow(json: Json, keys: Vector[String]): Either[DomainError, FrameMetrics] =
    val c = json.hcursor
    for
      ordinal <- MediaJson.int(c.downField("ordinal"), "metrics[].ordinal")
      values <- keys.foldLeft[Either[DomainError, Map[String, Double]]](Right(Map.empty)) {
        (acc, k) =>
          acc.flatMap { m =>
            val field = c.downField(k)
            if field.focus.forall(_.isNull) then Right(m)
            else MediaJson.double(field, s"metrics[].$k").map(v => m.updated(k, v))
          }
      }
    yield FrameMetrics(ordinal, values)

/** A boundary-existence and boundary-localization proposal on the shot layer: a visual
  * discontinuity between two consecutively presented frames, located at the instant the second was
  * presented, with the window between them and the detector's raw score. It carries no transition
  * morphology and no extent claim, so it is not a `BoundaryClaim` and offers no path to one;
  * morphology is a separate court with separately typed candidates (ledger §5).
  */
final class BoundaryLocalizationProposal private[media] (
    val id: BoundaryId,
    val layer: BoundaryLayer,
    val at: PlaybackInstant,
    val window: PlaybackInterval,
    val score: Double,
    val recipe: Checksum,
    val identity: Checksum
):
  override def toString: String = s"BoundaryLocalizationProposal($layer @ ${at.at}, score $score)"

/** One detector search over one frame set, joined. Empty output is `ExaminedNoCandidate` on the
  * examined extent and never a negative-boundary claim. Authority is `Draft`.
  */
final class BoundarySearchResult private[media] (
    val frames: FrameSet,
    val bundle: SourceBundle,
    val axis: PresentationAxis,
    val examined: PlaybackInterval,
    val proposals: Vector[BoundaryLocalizationProposal],
    val worker: ToolRealization,
    val recipe: ContentDetectorRecipe,
    val resolvedKernelSize: Int,
    val receipt: SourceDerivationReceipt,
    val identity: Checksum
):
  def authority: ObservationAuthority = ObservationAuthority.Draft

  /** The coverage record when the detector examined the extent and proposed nothing. */
  def noCandidate: Option[BoundarySearchCoverage] =
    if proposals.isEmpty then Some(BoundarySearchCoverage.ExaminedNoCandidate) else None

  override def toString: String =
    s"BoundarySearchResult(${frames.probe.manifest.fixtureId}, ${proposals.size} proposals, draft, ${identity.short()})"

object BoundarySearch:
  val Algorithm: String = "content-detector-boundary-search/v1"

  def parameters(
      worker: ToolRealization,
      recipe: ContentDetectorRecipe,
      requestId: String
  ): String =
    Vector("worker", worker.identity.hex, "recipe", recipe.identity.hex, "request", requestId)
      .mkString(" ")

  /** Join the issued request and the worker's outcome to the frame set. Refuses when the request
    * does not describe these frames; when the outcome answers a different request, different
    * frames, or a different recipe; when coverage is partial; when an automatic kernel came back
    * unresolved; when a cut has no preceding frame or is repeated; or when a cut has no score.
    * Every proposal's instant comes from the packet index.
    */
  def join(
      frames: FrameSet,
      request: DetectorRequest,
      outcome: DetectorOutcome,
      worker: ToolRealization
  ): Either[DomainError, BoundarySearchResult] =
    for
      _ <- requestDescribes(frames, request)
      _ <- outcomeAnswers(frames, request, outcome)
      resolved <- resolvedKernel(request.recipe, outcome)
      _ <- coverage(frames, outcome)
      _ <- metricsComplete(frames, outcome)
      _ <- cutsLawful(frames, outcome)
      endExclusive <- frames.index.endExclusive.toRight(
        DomainError.InvariantViolation(
          "boundary/extent",
          "the last frame's duration is unknown; the examined extent cannot be closed"
        )
      )
      edition <- EditionId.from(frames.probe.manifest.fixtureId)
      bundle <- SourceBundle.filmEdition(
        edition,
        frames.probe.input,
        frames.index.firstPts,
        endExclusive,
        frames.index.timebase
      )
      axis = bundle.primaryAxis
      examined <- PlaybackInterval.on(axis, frames.index.firstPts, endExclusive)
      receipt <- SourceDerivationReceipt.of(
        Algorithm,
        parameters(worker, request.recipe, request.requestId),
        Vector(frames.framesSha256, frames.identity)
      )
      proposals <- outcome.cuts.foldLeft[Either[DomainError, Vector[BoundaryLocalizationProposal]]](
        Right(Vector.empty)
      ) { (acc, cut) =>
        acc.flatMap(v => proposal(frames, axis, outcome, request.recipe, receipt, cut).map(v :+ _))
      }
    yield new BoundarySearchResult(
      frames,
      bundle,
      axis,
      examined,
      proposals,
      worker,
      request.recipe,
      resolved,
      receipt,
      ContentAddress.digest(
        Vector("boundary-search", receipt.identity.hex) ++ proposals.map(_.identity.hex)
      )
    )

  private def requestDescribes(
      frames: FrameSet,
      request: DetectorRequest
  ): Either[DomainError, Unit] =
    if request.framesSha256 == frames.framesSha256 && request.count == frames.count &&
      request.geometry == frames.geometry
    then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "boundary/request",
          s"request ${request.requestId} does not describe frame set ${frames.identity.short()}"
        )
      )

  private def outcomeAnswers(
      frames: FrameSet,
      request: DetectorRequest,
      outcome: DetectorOutcome
  ): Either[DomainError, Unit] =
    if outcome.requestId != request.requestId then
      Left(
        DomainError.InvariantViolation(
          "boundary/request-id",
          s"outcome answers ${outcome.requestId}, not ${request.requestId}"
        )
      )
    else if outcome.framesSha256 != frames.framesSha256 || outcome.framesCount != frames.count ||
      outcome.geometry != frames.geometry || outcome.pixelFormat != Ffmpeg.PixelFormat
    then
      Left(
        DomainError.InvariantViolation(
          "boundary/frames",
          s"outcome describes frames ${outcome.framesSha256.short()} (${outcome.framesCount}), not ${frames.framesSha256.short()} (${frames.count})"
        )
      )
    else if outcome.recipe != request.recipe then
      Left(
        DomainError.InvariantViolation(
          "boundary/recipe",
          s"outcome ran recipe ${outcome.recipe.identity.short()}, request issued ${request.recipe.identity.short()}"
        )
      )
    else Right(())

  private def resolvedKernel(
      recipe: ContentDetectorRecipe,
      outcome: DetectorOutcome
  ): Either[DomainError, Int] =
    (recipe.kernelSize, outcome.resolvedKernelSize) match
      case (Some(k), Some(r)) if k == r => Right(k)
      case (Some(k), None)              => Right(k)
      case (None, Some(r)) if r > 0     => Right(r)
      case (Some(k), Some(r))           =>
        Left(
          DomainError.InvariantViolation(
            "recipe/resolved",
            s"recipe declared kernel $k but the worker resolved $r"
          )
        )
      case (None, _) =>
        Left(
          DomainError.InvariantViolation(
            "recipe/resolved",
            "automatic kernel size came back unresolved; a library default may not stand in for a recorded value"
          )
        )

  private def coverage(frames: FrameSet, outcome: DetectorOutcome): Either[DomainError, Unit] =
    if outcome.coverageRequested == frames.count && outcome.coverageObserved == frames.count then
      Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "boundary/coverage",
          s"worker observed ${outcome.coverageObserved} of ${outcome.coverageRequested} requested frames; ${frames.count} were issued"
        )
      )

  private def metricsComplete(
      frames: FrameSet,
      outcome: DetectorOutcome
  ): Either[DomainError, Unit] =
    if outcome.metrics.size == frames.count &&
      outcome.metrics.zipWithIndex.forall { case (m, i) => m.ordinal == i }
    then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "boundary/metrics",
          s"expected one metric row per frame in order (${frames.count}), got ${outcome.metrics.size}"
        )
      )

  private def cutsLawful(frames: FrameSet, outcome: DetectorOutcome): Either[DomainError, Unit] =
    val outOfRange = outcome.cuts.find(k => k < 1 || k >= frames.count)
    val repeated =
      outcome.cuts.iterator.sliding(2).exists { case Seq(a, b) => b <= a; case _ => false }
    outOfRange match
      case Some(k) =>
        Left(
          DomainError.InvariantViolation(
            "boundary/cut",
            s"cut at ordinal $k has no preceding frame or lies outside ${frames.count} frames"
          )
        )
      case None if repeated =>
        Left(DomainError.InvariantViolation("boundary/cut", "cuts must be strictly increasing"))
      case None => Right(())

  private def proposal(
      frames: FrameSet,
      axis: PresentationAxis,
      outcome: DetectorOutcome,
      recipe: ContentDetectorRecipe,
      receipt: SourceDerivationReceipt,
      cut: Int
  ): Either[DomainError, BoundaryLocalizationProposal] =
    for
      after <- frames
        .ptsOf(cut)
        .toRight(DomainError.InvariantViolation("boundary/index", s"no frame $cut"))
      before <- frames
        .ptsOf(cut - 1)
        .toRight(DomainError.InvariantViolation("boundary/index", s"no frame ${cut - 1}"))
      score <- outcome.metrics
        .lift(cut)
        .flatMap(_.values.get(DetectorOutcome.ScoreKey))
        .toRight(
          DomainError.InvariantViolation(
            "boundary/score",
            s"cut at ordinal $cut carries no ${DetectorOutcome.ScoreKey}"
          )
        )
      at <- PlaybackInstant.on(axis, after)
      window <- PlaybackInterval.on(axis, before, after)
      id <- BoundaryId.from(
        ContentAddress.of("shot-boundary", frames.probe.manifest.fixtureId, after.toString)
      )
    yield new BoundaryLocalizationProposal(
      id,
      BoundaryLayer.Shot,
      at,
      window,
      score,
      recipe.identity,
      ContentAddress.digest(
        Vector(
          "boundary-proposal",
          receipt.identity.hex,
          id.value,
          before.toString,
          after.toString,
          score.toString
        )
      )
    )
