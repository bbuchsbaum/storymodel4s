package storymodel4s.media

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.core.{Checksum, DomainError, ObservationAuthority}

/** The captioning court over F0 v1 (captioning admission record §5): the pinned Qwen3-VL-4B
  * revision, driven by the untrusted worker over frames the media module identified, joined into
  * timed visual-description proposals. F0's flat colours are the only ground truth and they test
  * the plumbing, not the model: no test says what the text must be, only that it is bound to the
  * right frames, interval, model, recipe, and worker, and that nothing here is more than `Draft`.
  * The recorded outcome replays in ordinary CI; the live court at the end needs the local worker
  * environment and the verified weights and obtains neither.
  */
class CaptionSearchSuite extends FunSuite:

  private def resource(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/f0/$name")
    assert(in != null, s"missing test resource $name")
    try in.readAllBytes()
    finally in.close()

  private def text(name: String): String = new String(resource(name), StandardCharsets.UTF_8)

  private def right[A](e: Either[DomainError, A]): A =
    e.fold(err => fail(s"expected Right, got ${err.message}"), identity)

  private def refusedAt(e: Either[DomainError, Any], path: String): Unit =
    e match
      case Left(DomainError.InvariantViolation(p, _)) => assertEquals(p, path)
      case Left(other) => fail(s"refused at the wrong place: ${other.message}")
      case Right(v)    => fail(s"expected refusal at $path, got $v")

  private lazy val bytes: Array[Byte] = resource("f0-v1.mov")
  private lazy val manifest: FixtureManifest =
    right(FixtureManifest.parse(text("f0-v1.manifest.json")))
  private lazy val probeEnvelope: ProbeEnvelope =
    right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
  private lazy val probe: MediaProbe =
    right(
      MediaProbe.join(
        manifest,
        right(manifest.verify(bytes)),
        probeEnvelope.tool,
        probeEnvelope.args,
        right(
          FfprobeJson.parse(right(probeEnvelope.verifyStdout(resource(probeEnvelope.stdoutFile))))
        )
      )
    )
  private lazy val framesEnvelope: FramesEnvelope =
    right(FramesEnvelope.parse(text("f0-v1.frames-envelope.json")))
  private lazy val frames: FrameSet =
    right(
      FrameSet.join(
        probe,
        framesEnvelope.streamIndex,
        framesEnvelope.geometry,
        framesEnvelope.tool,
        framesEnvelope.args,
        framesEnvelope.byteLength,
        framesEnvelope.framesSha256
      )
    )
  private lazy val envelope: CaptionEnvelope =
    right(CaptionEnvelope.parse(text("f0-v1.caption-envelope.json")))
  private lazy val request: CaptionRequest =
    right(CaptionRequest.parse(text(envelope.requestFile)))
  private lazy val outcome: CaptionOutcome =
    right(CaptionOutcome.parse(right(envelope.verifyOutcome(resource(envelope.outcomeFile)))))
  private lazy val result: CaptionSearchResult =
    right(CaptionSearch.join(frames, request, outcome, envelope.worker))

  private def joinWith(
      req: CaptionRequest = request,
      out: CaptionOutcome = outcome,
      worker: ToolRealization = envelope.worker
  ): Either[DomainError, CaptionSearchResult] = CaptionSearch.join(frames, req, out, worker)

  test(
    "the recorded outcome replays into one draft proposal per extent, each spanning its frames on the axis"
  ):
    assertEquals(result.authority, ObservationAuthority.Draft)
    assertEquals(result.proposals.size, 3)
    assertEquals(result.proposals.map(_.extentId), Vector("shot-1", "shot-2", "shot-3"))
    // Extents are sampled frames of the three declared shots; their supports close on the last
    // frame's duration from the packet index, so they tile the declared shot intervals exactly.
    assertEquals(
      result.proposals.map(p => (p.support.start, p.support.endExclusive)),
      Vector((0L, 16000L), (16000L, 32000L), (32000L, 48000L))
    )
    assert(result.proposals.forall(_.support.axis == result.axis.id))
    assert(
      result.proposals.forall(_.text.trim.nonEmpty),
      "the model wrote something for every extent"
    )
    assert(result.proposals.forall(_.recipe == request.recipe.identity))
    assert(result.proposals.forall(_.model == request.model.identity))
    assertEquals(result.proposals.map(_.identity).distinct.size, 3)
    assert(result.receipt.parameters.contains(BoundarySearch.EditListAssumption))
    assert(result.receipt.parameters.contains(request.model.identity.hex))
    assertEquals(outcome.model.revision, "ebb281ec70b0")
    assert(outcome.model.shardsVerified)
    assertEquals(outcome.runtimeLine, envelope.worker.versionLine)

  test("replay is identical; another worker, recipe, or model is another derivation"):
    val again = right(joinWith())
    assertEquals(again.identity, result.identity)
    val otherWorker = right(
      ToolRealization.of(
        WorkerRealization.CaptionToolName,
        envelope.worker.versionLine,
        Checksum.ofText("other")
      )
    )
    assertNotEquals(right(joinWith(worker = otherWorker)).identity, result.identity)
    assertNotEquals(
      request.recipe.copy(prompt = request.recipe.prompt + " ").identity,
      request.recipe.identity
    )
    assertNotEquals(request.model.copy(revision = "0c351dd01ed8").identity, request.model.identity)

  test(
    "an outcome for another request, other frames, another recipe, another model, or another runtime is refused"
  ):
    refusedAt(joinWith(out = outcome.copy(requestId = "someone-else")), "caption/request-id")
    refusedAt(
      joinWith(out = outcome.copy(framesSha256 = Checksum.ofText("other frames"))),
      "caption/frames"
    )
    refusedAt(
      joinWith(req = request.copy(recipe = request.recipe.copy(maxNewTokens = 8))),
      "caption/recipe"
    )
    refusedAt(
      joinWith(out = outcome.copy(model = outcome.model.copy(revision = "0c351dd01ed8"))),
      "caption/model"
    )
    refusedAt(
      joinWith(out = outcome.copy(model = outcome.model.copy(shardsVerified = false))),
      "caption/model"
    )
    refusedAt(
      joinWith(out = outcome.copy(runtime = outcome.runtime.updated("mlx_vlm", "0.0.0"))),
      "caption/worker"
    )
    refusedAt(joinWith(req = request.copy(framesSha256 = Checksum.ofText("x"))), "caption/request")

  test(
    "what the library applied must satisfy the recipe; coverage and extents must answer the request"
  ):
    refusedAt(
      joinWith(out = outcome.copy(applied = outcome.applied.copy(maxPixels = 4096))),
      "caption/applied"
    )
    refusedAt(
      joinWith(out =
        outcome.copy(applied = outcome.applied.copy(promptSha256 = Checksum.ofText("other prompt")))
      ),
      "caption/applied"
    )
    refusedAt(joinWith(out = outcome.copy(coverageObserved = 2)), "caption/coverage")
    refusedAt(
      joinWith(out = outcome.copy(extents = outcome.extents.dropRight(1))),
      "caption/coverage"
    )
    val swapped = outcome.copy(extents =
      outcome.extents.map(e => if e.id == "shot-2" then e.copy(ordinals = Vector(14, 20)) else e)
    )
    refusedAt(joinWith(out = swapped), "caption/extent")

  test(
    "an extent must name frames of the set, in increasing order, and cannot be issued otherwise"
  ):
    assert(CaptionExtent.of("x", Vector.empty).isLeft)
    assert(CaptionExtent.of("x", Vector(3, 3)).isLeft)
    assert(CaptionExtent.of("x", Vector(-1)).isLeft)
    val outside = right(CaptionExtent.of("beyond", Vector(44, 45)))
    refusedAt(
      CaptionRequest.issue(frames, Vector(outside), request.model, request.recipe, "r"),
      "extent/ordinals"
    )
    val dup =
      Vector(right(CaptionExtent.of("a", Vector(0))), right(CaptionExtent.of("a", Vector(1))))
    refusedAt(CaptionRequest.issue(frames, dup, request.model, request.recipe, "r"), "extent/id")
    assert(
      CaptionRecipe
        .parse(
          io.circe.parser
            .parse(
              request.recipe.json
                .deepMerge(io.circe.Json.obj("greedy" -> io.circe.Json.fromBoolean(false)))
                .noSpaces
            )
            .toOption
            .get
            .hcursor
        )
        .isLeft
    )

  test("a caption proposal is not a timed segment and offers no promotion"):
    // Type-level: CaptionProposal has no conversion to TimedSegment or to any accepted claim; the
    // only path is a receipt-carrying adapter that does not exist yet. What can be asserted here:
    // the proposal carries its provenance and the search stays Draft.
    val p = result.proposals.head
    assertEquals(p.recipe, request.recipe.identity)
    assertEquals(p.model, request.model.identity)
    assertEquals(result.authority, ObservationAuthority.Draft)

  test("live worker and weights, when this machine has them, reproduce the recorded proposals"):
    val python = CaptionSearchSuite.workerPython
    val script = CaptionSearchSuite.workerScript
    val model = CaptionSearchSuite.modelDir
    val ffmpeg = Ffmpeg.locate()
    assume(
      python.isDefined && script.isDefined,
      "caption worker environment not installed; live court not run"
    )
    assume(
      model.isDefined,
      "pinned weights not present locally (tmp/models or STORYMODEL4S_QWEN3VL_4B); live court not run, nothing is downloaded"
    )
    assume(ffmpeg.isDefined, "no ffmpeg on PATH; live court not run")
    val dir = Files.createTempDirectory("f0-caption-")
    try
      val input = dir.resolve("f0-v1.mov")
      Files.write(input, bytes)
      val raw = dir.resolve("f0-v1.frames.bgr")
      val decodeTool = right(ToolRealization.observe(Ffmpeg.ToolName, ffmpeg.get))
      right(Ffmpeg.extract(ffmpeg.get, input, 0, raw))
      val decoded = Files.readAllBytes(raw)
      val live = right(
        FrameSet.join(
          probe,
          0,
          framesEnvelope.geometry,
          decodeTool,
          Ffmpeg.canonicalArgs(0),
          decoded.length.toLong,
          Checksum.ofBytes(decoded)
        )
      )
      assume(
        live.framesSha256 == frames.framesSha256,
        "decoder drift; the recorded caption run is not comparable"
      )
      val issued = right(
        CaptionRequest.issue(
          live,
          request.extents,
          request.model,
          request.recipe,
          request.requestId
        )
      )
      val reqPath = dir.resolve("request.json")
      val outPath = dir.resolve("outcome.json")
      Files.writeString(reqPath, issued.render(raw.toString, model.get.toString))
      val run = right(
        Subprocess.run(
          Vector(python.get.toString, script.get.toString, reqPath.toString, outPath.toString),
          scala.concurrent.duration.Duration(20, "min")
        )
      )
      assertEquals(run.exitCode, 0, run.stderr.takeRight(400))
      val liveOutcome = right(CaptionOutcome.parse(Files.readString(outPath)))
      val worker = right(WorkerRealization.observeCaption(python.get, script.get))
      val liveResult = right(CaptionSearch.join(live, issued, liveOutcome, worker))
      assertEquals(
        liveResult.proposals.map(p => (p.support.start, p.support.endExclusive)),
        result.proposals.map(p => (p.support.start, p.support.endExclusive))
      )
      // Greedy decoding on the same weights and frames: the same text is expected, and a difference
      // is reported as drift rather than hidden.
      if worker == envelope.worker then
        assertEquals(liveResult.proposals.map(_.text), result.proposals.map(_.text))
    finally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
      ()

object CaptionSearchSuite:
  private def existing(candidates: Vector[Path]): Option[Path] =
    candidates.find(Files.isRegularFile(_))
  private def existingDir(candidates: Vector[Path]): Option[Path] =
    candidates.find(Files.isDirectory(_))

  def workerPython: Option[Path] =
    Option(System.getenv("STORYMODEL4S_CAPTION_WORKER_PYTHON"))
      .map(Path.of(_))
      .filter(Files.isRegularFile(_))
      .orElse(
        existing(
          Vector(
            Path.of("media/worker-vlm/.venv/bin/python"),
            Path.of("worker-vlm/.venv/bin/python")
          )
        )
      )

  def workerScript: Option[Path] =
    existing(
      Vector(Path.of("media/worker-vlm/caption_worker.py"), Path.of("worker-vlm/caption_worker.py"))
    )

  /** The verified local weights, if present. Never downloaded by the court. */
  def modelDir: Option[Path] =
    Option(System.getenv("STORYMODEL4S_QWEN3VL_4B"))
      .map(Path.of(_))
      .filter(Files.isDirectory(_))
      .orElse(
        existingDir(
          Vector(
            Path.of("tmp/models/Qwen3-VL-4B-Instruct"),
            Path.of("../tmp/models/Qwen3-VL-4B-Instruct")
          )
        )
      )
