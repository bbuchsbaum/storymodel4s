package storymodel4s.media

import io.circe.{ACursor, Json}
import storymodel4s.core.{
  Checksum,
  ContentAddress,
  DomainError,
  EditionId,
  ObservationAuthority,
  PlaybackInterval,
  PresentationAxis,
  SourceBundle,
  SourceDerivationReceipt
}

/** The exact model a caption request is issued against: repository, revision, and the digest of
  * every weight shard. The worker verifies the local directory against these before loading and
  * echoes them; the join refuses an outcome that names anything else.
  */
final case class ModelPin(repo: String, revision: String, shards: Map[String, Checksum]):
  def identity: Checksum =
    ContentAddress.digest(
      Vector("model-pin", repo, revision) ++ shards.toVector.sortBy(_._1).flatMap { case (n, c) =>
        Vector(n, c.hex)
      }
    )
  def json: Json =
    Json.obj(
      "repo" -> Json.fromString(repo),
      "revision" -> Json.fromString(revision),
      "shardsSha256" -> Json.obj(shards.toVector.sortBy(_._1).map { case (n, c) =>
        n -> Json.fromString(c.hex)
      }*)
    )

object ModelPin:
  def parse(c: ACursor): Either[DomainError, ModelPin] =
    for
      repo <- MediaJson.string(c.downField("repo"), "model.repo")
      revision <- MediaJson.string(c.downField("revision"), "model.revision")
      shardsJson <- c
        .downField("shardsSha256")
        .as[Map[String, String]]
        .left
        .map(_ =>
          DomainError.InvalidFormat("model.shardsSha256", "<object>", "expected name -> sha256")
        )
      shards <- shardsJson.toVector.foldLeft[Either[DomainError, Map[String, Checksum]]](
        Right(Map.empty)
      ) { case (acc, (n, hex)) =>
        acc.flatMap(m => Checksum.from(hex).map(c => m.updated(n, c)))
      }
      _ <-
        if shards.nonEmpty then Right(())
        else
          Left(
            DomainError.InvariantViolation("model/shards", "a model pin names at least one shard")
          )
    yield ModelPin(repo, revision, shards)

/** Every captioning parameter, declared. Frames are always supplied explicitly as an image
  * sequence; decoding is greedy; the prompt is one fixed text whose digest enters every receipt.
  */
final case class CaptionRecipe(
    prompt: String,
    minPixels: Int,
    maxPixels: Int,
    maxNewTokens: Int,
    seed: Int
):
  val presentation: String = "image-sequence"
  def promptSha256: Checksum = Checksum.ofText(prompt)
  def identity: Checksum =
    ContentAddress.digest(
      Vector(
        "caption-recipe",
        promptSha256.hex,
        minPixels.toString,
        maxPixels.toString,
        maxNewTokens.toString,
        seed.toString,
        presentation,
        "greedy"
      )
    )
  def json: Json =
    Json.obj(
      "prompt" -> Json.fromString(prompt),
      "minPixels" -> Json.fromInt(minPixels),
      "maxPixels" -> Json.fromInt(maxPixels),
      "maxNewTokens" -> Json.fromInt(maxNewTokens),
      "seed" -> Json.fromInt(seed),
      "presentation" -> Json.fromString(presentation),
      "greedy" -> Json.fromBoolean(true)
    )

object CaptionRecipe:
  def parse(c: ACursor): Either[DomainError, CaptionRecipe] =
    for
      prompt <- MediaJson.string(c.downField("prompt"), "recipe.prompt")
      minPixels <- MediaJson.int(c.downField("minPixels"), "recipe.minPixels")
      maxPixels <- MediaJson.int(c.downField("maxPixels"), "recipe.maxPixels")
      maxNewTokens <- MediaJson.int(c.downField("maxNewTokens"), "recipe.maxNewTokens")
      seed <- MediaJson.int(c.downField("seed"), "recipe.seed")
      presentation <- MediaJson.string(c.downField("presentation"), "recipe.presentation")
      greedy <- MediaJson.boolean(c.downField("greedy"), "recipe.greedy")
      _ <-
        if presentation == "image-sequence" && greedy then Right(())
        else
          Left(
            DomainError.InvalidFormat(
              "recipe",
              s"$presentation/greedy=$greedy",
              "only the greedy image-sequence recipe is admitted"
            )
          )
      _ <-
        if prompt.trim.nonEmpty && minPixels > 0 && maxPixels >= minPixels && maxNewTokens > 0 then
          Right(())
        else Left(DomainError.InvariantViolation("recipe", "empty prompt or non-positive limits"))
    yield CaptionRecipe(prompt, minPixels, maxPixels, maxNewTokens, seed)

/** One extent to describe: a nonempty, strictly increasing list of frame ordinals. */
final case class CaptionExtent(id: String, ordinals: Vector[Int])

object CaptionExtent:
  def of(id: String, ordinals: Vector[Int]): Either[DomainError, CaptionExtent] =
    if id.trim.isEmpty then Left(DomainError.InvariantViolation("extent/id", "empty extent id"))
    else if ordinals.isEmpty then
      Left(DomainError.InvariantViolation("extent/ordinals", s"extent $id names no frames"))
    else if ordinals.exists(_ < 0) || ordinals.iterator.sliding(2).exists {
        case Seq(a, b) => b <= a
        case _         => false
      }
    then
      Left(
        DomainError.InvariantViolation(
          "extent/ordinals",
          s"extent $id must list frame ordinals strictly increasing and non-negative"
        )
      )
    else Right(CaptionExtent(id, ordinals))

  def parse(json: Json): Either[DomainError, CaptionExtent] =
    val c = json.hcursor
    for
      id <- MediaJson.string(c.downField("id"), "extents[].id")
      ordJson <- MediaJson.array(c.downField("ordinals"), "extents[].ordinals")
      ordinals <- MediaJson.traverse(ordJson)(j => MediaJson.int(j.hcursor, "extents[].ordinals[]"))
      extent <- of(id, ordinals)
    yield extent

/** The request the adapter issues to the captioning worker. */
final case class CaptionRequest(
    requestId: String,
    framesSha256: Checksum,
    count: Int,
    geometry: PictureGeometry,
    extents: Vector[CaptionExtent],
    model: ModelPin,
    recipe: CaptionRecipe
):
  /** Wire form; `frameFile` and `modelPath` are the local paths the worker reads. */
  def render(frameFile: String, modelPath: String): String =
    Json
      .obj(
        "schema" -> Json.fromString(CaptionRequest.Schema),
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
        "extents" -> Json.arr(
          extents.map(e =>
            Json.obj(
              "id" -> Json.fromString(e.id),
              "ordinals" -> Json.arr(e.ordinals.map(Json.fromInt)*)
            )
          )*
        ),
        "model" -> model.json.deepMerge(Json.obj("localPath" -> Json.fromString(modelPath))),
        "recipe" -> recipe.json
      )
      .spaces2

object CaptionRequest:
  val Schema: String = "storymodel4s.media.caption-request"

  def issue(
      frames: FrameSet,
      extents: Vector[CaptionExtent],
      model: ModelPin,
      recipe: CaptionRecipe,
      requestId: String
  ): Either[DomainError, CaptionRequest] =
    extents.find(e => e.ordinals.exists(_ >= frames.count)) match
      case Some(e) =>
        Left(
          DomainError.InvariantViolation(
            "extent/ordinals",
            s"extent ${e.id} names a frame outside the ${frames.count}-frame set"
          )
        )
      case None if extents.map(_.id).distinct.size != extents.size =>
        Left(DomainError.InvariantViolation("extent/id", "extent ids must be distinct"))
      case None =>
        Right(
          CaptionRequest(
            requestId,
            frames.framesSha256,
            frames.count,
            frames.geometry,
            extents,
            model,
            recipe
          )
        )

  def parse(text: String): Either[DomainError, CaptionRequest] =
    for
      json <- MediaJson.parseDocument("caption-request", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      requestId <- MediaJson.string(c.downField("requestId"), "requestId")
      f = c.downField("frames")
      sha <- MediaJson.string(f.downField("sha256"), "frames.sha256").flatMap(Checksum.from)
      count <- MediaJson.int(f.downField("count"), "frames.count")
      width <- MediaJson.int(f.downField("width"), "frames.width")
      height <- MediaJson.int(f.downField("height"), "frames.height")
      extentsJson <- MediaJson.array(c.downField("extents"), "extents")
      extents <- MediaJson.traverse(extentsJson)(CaptionExtent.parse)
      model <- ModelPin.parse(c.downField("model"))
      recipe <- CaptionRecipe.parse(c.downField("recipe"))
    yield CaptionRequest(
      requestId,
      sha,
      count,
      PictureGeometry(width, height),
      extents,
      model,
      recipe
    )

/** What the library applied, read back from the processor rather than echoed. */
final case class AppliedCaptionRecipe(
    promptSha256: Checksum,
    minPixels: Int,
    maxPixels: Int,
    maxNewTokens: Int,
    greedy: Boolean,
    patchSize: Option[Int],
    temporalPatchSize: Option[Int],
    mergeSize: Option[Int]
):
  def satisfies(recipe: CaptionRecipe): Boolean =
    promptSha256 == recipe.promptSha256 && minPixels == recipe.minPixels &&
      maxPixels == recipe.maxPixels && maxNewTokens == recipe.maxNewTokens && greedy

object AppliedCaptionRecipe:
  def parse(c: ACursor): Either[DomainError, AppliedCaptionRecipe] =
    for
      prompt <- MediaJson
        .string(c.downField("promptSha256"), "applied.promptSha256")
        .flatMap(Checksum.from)
      minPixels <- MediaJson.int(c.downField("minPixels"), "applied.minPixels")
      maxPixels <- MediaJson.int(c.downField("maxPixels"), "applied.maxPixels")
      maxNewTokens <- MediaJson.int(c.downField("maxNewTokens"), "applied.maxNewTokens")
      greedy <- MediaJson.boolean(c.downField("greedy"), "applied.greedy")
      patch <- MediaJson.optionalInt(c.downField("patchSize"), "applied.patchSize")
      temporal <- MediaJson.optionalInt(
        c.downField("temporalPatchSize"),
        "applied.temporalPatchSize"
      )
      merge <- MediaJson.optionalInt(c.downField("mergeSize"), "applied.mergeSize")
    yield AppliedCaptionRecipe(
      prompt,
      minPixels,
      maxPixels,
      maxNewTokens,
      greedy,
      patch,
      temporal,
      merge
    )

/** One described extent as the worker returned it. */
final case class CaptionResult(
    id: String,
    ordinals: Vector[Int],
    text: String,
    promptTokens: Option[Int],
    generatedTokens: Option[Int]
)

/** The model as the outcome names it. */
final case class ModelEcho(
    repo: String,
    revision: String,
    configSha256: Checksum,
    shardsVerified: Boolean
)

/** What the captioning worker returned, parsed and nothing more. */
final case class CaptionOutcome(
    requestId: String,
    runtime: Map[String, String],
    model: ModelEcho,
    framesSha256: Checksum,
    framesCount: Int,
    geometry: PictureGeometry,
    pixelFormat: String,
    recipe: CaptionRecipe,
    applied: AppliedCaptionRecipe,
    extents: Vector[CaptionResult],
    coverageRequested: Int,
    coverageObserved: Int
):
  def runtimeLine: String = CaptionOutcome.runtimeLine(runtime)

object CaptionOutcome:
  val Schema: String = "storymodel4s.media.caption-outcome"
  val RuntimeKeys: Vector[String] = Vector("mlx_vlm", "mlx", "python")

  def runtimeLine(runtime: Map[String, String]): String =
    RuntimeKeys.flatMap(k => runtime.get(k).map(v => s"$k $v")).mkString(" ")

  def parse(text: String): Either[DomainError, CaptionOutcome] =
    for
      json <- MediaJson.parseDocument("caption-outcome", text)
      c = json.hcursor
      _ <- MediaJson.expectSchema(c, Schema, 1)
      requestId <- MediaJson.string(c.downField("requestId"), "requestId")
      runtime <- MediaJson.stringMap(c.downField("runtime"), "runtime")
      _ <-
        if RuntimeKeys.forall(runtime.contains) then Right(())
        else
          Left(
            DomainError.InvalidFormat(
              "runtime",
              runtime.keys.toVector.sorted.mkString(","),
              s"expected ${RuntimeKeys.mkString(", ")}"
            )
          )
      m = c.downField("model")
      repo <- MediaJson.string(m.downField("repo"), "model.repo")
      revision <- MediaJson.string(m.downField("revision"), "model.revision")
      configSha <- MediaJson
        .string(m.downField("configSha256"), "model.configSha256")
        .flatMap(Checksum.from)
      verified <- MediaJson.boolean(m.downField("shardsVerified"), "model.shardsVerified")
      f = c.downField("frames")
      sha <- MediaJson.string(f.downField("sha256"), "frames.sha256").flatMap(Checksum.from)
      count <- MediaJson.int(f.downField("count"), "frames.count")
      width <- MediaJson.int(f.downField("width"), "frames.width")
      height <- MediaJson.int(f.downField("height"), "frames.height")
      pix <- MediaJson.string(f.downField("pixelFormat"), "frames.pixelFormat")
      recipe <- CaptionRecipe.parse(c.downField("recipe"))
      applied <- AppliedCaptionRecipe.parse(c.downField("applied"))
      extentsJson <- MediaJson.array(c.downField("extents"), "extents")
      extents <- MediaJson.traverse(extentsJson)(parseResult)
      cov = c.downField("coverage")
      requested <- MediaJson.int(cov.downField("requested"), "coverage.requested")
      observed <- MediaJson.int(cov.downField("observed"), "coverage.observed")
    yield CaptionOutcome(
      requestId,
      runtime,
      ModelEcho(repo, revision, configSha, verified),
      sha,
      count,
      PictureGeometry(width, height),
      pix,
      recipe,
      applied,
      extents,
      requested,
      observed
    )

  private def parseResult(json: Json): Either[DomainError, CaptionResult] =
    val c = json.hcursor
    for
      id <- MediaJson.string(c.downField("id"), "extents[].id")
      ordJson <- MediaJson.array(c.downField("ordinals"), "extents[].ordinals")
      ordinals <- MediaJson.traverse(ordJson)(j => MediaJson.int(j.hcursor, "extents[].ordinals[]"))
      text <- MediaJson.string(c.downField("text"), "extents[].text")
      promptTokens <- MediaJson.optionalInt(c.downField("promptTokens"), "extents[].promptTokens")
      generated <- MediaJson.optionalInt(
        c.downField("generatedTokens"),
        "extents[].generatedTokens"
      )
    yield CaptionResult(id, ordinals, text, promptTokens, generated)

/** Recorded captioning run: the worker realization, the model pin, the request it answered, and the
  * digest of the outcome it wrote.
  */
final case class CaptionEnvelope(
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

object CaptionEnvelope:
  val Schema: String = "storymodel4s.media.caption-envelope"

  def parse(text: String): Either[DomainError, CaptionEnvelope] =
    for
      json <- MediaJson.parseDocument("caption-envelope", text)
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
    yield CaptionEnvelope(fixtureId, worker, requestFile, outcomeFile, outcomeSha)

/** A timed visual description proposed by the model for one extent: the text, the frames shown, the
  * playback interval those frames span on the picture stream's axis, and the identities that
  * produced it. It is a `Proposal` with `Draft` authority: it says what a model wrote about these
  * frames, not what happened in the film, and it is not a `TimedSegment`.
  */
final class CaptionProposal private[media] (
    val extentId: String,
    val support: PlaybackInterval,
    val frames: Vector[Int],
    val text: String,
    val generatedTokens: Option[Int],
    val recipe: Checksum,
    val model: Checksum,
    val identity: Checksum
):
  override def toString: String =
    s"CaptionProposal($extentId [${support.start}, ${support.endExclusive}) ${text.take(40)}…)"

/** One captioning run over one frame set, joined. Authority is `Draft`. The axis is the picture
  * stream's native clock admitted as the edition axis under the same recorded identity-edit-list
  * assumption as the boundary court, and checked the same way.
  */
final class CaptionSearchResult private[media] (
    val frames: FrameSet,
    val bundle: SourceBundle,
    val axis: PresentationAxis,
    val proposals: Vector[CaptionProposal],
    val worker: ToolRealization,
    val model: ModelPin,
    val recipe: CaptionRecipe,
    val applied: AppliedCaptionRecipe,
    val receipt: SourceDerivationReceipt,
    val identity: Checksum
):
  def authority: ObservationAuthority = ObservationAuthority.Draft
  override def toString: String =
    s"CaptionSearchResult(${frames.probe.manifest.fixtureId}, ${proposals.size} proposals, draft, ${identity.short()})"

object CaptionSearch:
  val Algorithm: String = "vlm-caption-proposals/v1"

  def parameters(
      worker: ToolRealization,
      model: ModelPin,
      recipe: CaptionRecipe,
      requestId: String
  ): String =
    Vector(
      "worker",
      worker.identity.hex,
      "model",
      model.identity.hex,
      "recipe",
      recipe.identity.hex,
      "request",
      requestId,
      BoundarySearch.EditListAssumption
    ).mkString(" ")

  /** Join the issued request and the worker's outcome to the frame set. Refuses when the request
    * does not describe these frames; when the outcome answers another request, other frames,
    * another recipe, another model, or claims a runtime other than the worker it is joined under;
    * when the worker did not verify the shards; when what the library applied does not satisfy the
    * recipe; when coverage is partial or a result does not match its extent; or when an extent's
    * frames cannot be closed on the axis. Every interval comes from the packet index.
    */
  def join(
      frames: FrameSet,
      request: CaptionRequest,
      outcome: CaptionOutcome,
      worker: ToolRealization
  ): Either[DomainError, CaptionSearchResult] =
    for
      _ <- BoundarySearch.identityEditList(frames)
      _ <- requestDescribes(frames, request)
      _ <- outcomeAnswers(frames, request, outcome, worker)
      _ <- appliedMatches(request.recipe, outcome.applied)
      _ <- coverage(request, outcome)
      endExclusive <- frames.index.endExclusive.toRight(
        DomainError.InvariantViolation(
          "caption/extent",
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
      receipt <- SourceDerivationReceipt.of(
        Algorithm,
        parameters(worker, request.model, request.recipe, request.requestId),
        Vector(frames.framesSha256, frames.identity)
      )
      proposals <- request.extents
        .zip(outcome.extents)
        .foldLeft[Either[DomainError, Vector[CaptionProposal]]](Right(Vector.empty)) {
          case (acc, (extent, result)) =>
            acc.flatMap(v => proposal(frames, axis, request, receipt, extent, result).map(v :+ _))
        }
    yield new CaptionSearchResult(
      frames,
      bundle,
      axis,
      proposals,
      worker,
      request.model,
      request.recipe,
      outcome.applied,
      receipt,
      ContentAddress.digest(
        Vector("caption-search", receipt.identity.hex) ++ proposals.map(_.identity.hex)
      )
    )

  private def requestDescribes(
      frames: FrameSet,
      request: CaptionRequest
  ): Either[DomainError, Unit] =
    if request.framesSha256 == frames.framesSha256 && request.count == frames.count &&
      request.geometry == frames.geometry && request.extents.forall(
        _.ordinals.forall(_ < frames.count)
      )
    then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "caption/request",
          s"request ${request.requestId} does not describe frame set ${frames.identity.short()}"
        )
      )

  private def outcomeAnswers(
      frames: FrameSet,
      request: CaptionRequest,
      outcome: CaptionOutcome,
      worker: ToolRealization
  ): Either[DomainError, Unit] =
    if outcome.requestId != request.requestId then
      Left(
        DomainError.InvariantViolation(
          "caption/request-id",
          s"outcome answers ${outcome.requestId}, not ${request.requestId}"
        )
      )
    else if outcome.framesSha256 != frames.framesSha256 || outcome.framesCount != frames.count ||
      outcome.geometry != frames.geometry || outcome.pixelFormat != Ffmpeg.PixelFormat
    then
      Left(
        DomainError.InvariantViolation(
          "caption/frames",
          s"outcome describes frames ${outcome.framesSha256.short()} (${outcome.framesCount}), not ${frames.framesSha256.short()} (${frames.count})"
        )
      )
    else if outcome.recipe != request.recipe then
      Left(
        DomainError.InvariantViolation(
          "caption/recipe",
          s"outcome echoes recipe ${outcome.recipe.identity.short()}, request issued ${request.recipe.identity.short()}"
        )
      )
    else if outcome.model.repo != request.model.repo || outcome.model.revision != request.model.revision ||
      !outcome.model.shardsVerified
    then
      Left(
        DomainError.InvariantViolation(
          "caption/model",
          s"outcome names ${outcome.model.repo}@${outcome.model.revision} (verified ${outcome.model.shardsVerified}), request pinned ${request.model.repo}@${request.model.revision}"
        )
      )
    else if outcome.runtimeLine != worker.versionLine then
      Left(
        DomainError.InvariantViolation(
          "caption/worker",
          s"outcome claims runtime '${outcome.runtimeLine}', joined under worker '${worker.versionLine}'"
        )
      )
    else Right(())

  private def appliedMatches(
      recipe: CaptionRecipe,
      applied: AppliedCaptionRecipe
  ): Either[DomainError, Unit] =
    if applied.satisfies(recipe) then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "caption/applied",
          s"the library applied $applied, which does not satisfy recipe ${recipe.identity.short()}"
        )
      )

  private def coverage(
      request: CaptionRequest,
      outcome: CaptionOutcome
  ): Either[DomainError, Unit] =
    val n = request.extents.size
    if outcome.coverageRequested == n && outcome.coverageObserved == n && outcome.extents.size == n
    then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          "caption/coverage",
          s"worker observed ${outcome.coverageObserved} of ${outcome.coverageRequested} requested extents with ${outcome.extents.size} results; $n were issued"
        )
      )

  private def proposal(
      frames: FrameSet,
      axis: PresentationAxis,
      request: CaptionRequest,
      receipt: SourceDerivationReceipt,
      extent: CaptionExtent,
      result: CaptionResult
  ): Either[DomainError, CaptionProposal] =
    for
      _ <-
        if result.id == extent.id && result.ordinals == extent.ordinals then Right(())
        else
          Left(
            DomainError.InvariantViolation(
              "caption/extent",
              s"result ${result.id} ${result.ordinals.mkString(",")} does not answer extent ${extent.id} ${extent.ordinals.mkString(",")}"
            )
          )
      first <- frames
        .ptsOf(extent.ordinals.head)
        .toRight(
          DomainError.InvariantViolation("caption/index", s"no frame ${extent.ordinals.head}")
        )
      lastEntry <- frames.index.entries
        .lift(extent.ordinals.last)
        .toRight(
          DomainError.InvariantViolation("caption/index", s"no frame ${extent.ordinals.last}")
        )
      end <- lastEntry.durationTicks
        .map(d => lastEntry.pts + d)
        .toRight(
          DomainError.InvariantViolation(
            "caption/extent",
            s"frame ${extent.ordinals.last} has unknown duration; the extent cannot be closed"
          )
        )
      support <- PlaybackInterval.on(axis, first, end)
    yield new CaptionProposal(
      extent.id,
      support,
      extent.ordinals,
      result.text,
      result.generatedTokens,
      request.recipe.identity,
      request.model.identity,
      ContentAddress.digest(
        Vector(
          "caption-proposal",
          receipt.identity.hex,
          extent.id,
          first.toString,
          end.toString,
          Checksum.ofText(result.text).hex
        )
      )
    )
