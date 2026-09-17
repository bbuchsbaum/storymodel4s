package storymodel4s.media

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.core.{
  BoundaryClaim,
  BoundaryLayer,
  BoundarySearchCoverage,
  Checksum,
  ContentAddress,
  DomainError,
  ObservationAuthority,
  ShotMorphology
}

/** The boundary half of the first acquisition court over F0 v1 (ledger §6 items 3 and 4): decoded
  * frames bound to the packet index, a classical detector run through the direct frame interface,
  * and its output joined into shot-layer localization proposals that carry no morphology. The
  * recorded worker outcome replays in ordinary CI; the live court at the end needs a local ffmpeg
  * and the locally installed worker environment and obtains neither.
  */
class BoundarySearchSuite extends FunSuite:

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
  private lazy val output: FfprobeOutput =
    right(FfprobeJson.parse(right(probeEnvelope.verifyStdout(resource(probeEnvelope.stdoutFile)))))
  private lazy val probe: MediaProbe =
    right(
      MediaProbe.join(
        manifest,
        right(manifest.verify(bytes)),
        probeEnvelope.tool,
        probeEnvelope.args,
        output
      )
    )
  private lazy val framesEnvelope: FramesEnvelope =
    right(FramesEnvelope.parse(text("f0-v1.frames-envelope.json")))
  private def frameSetOf(p: MediaProbe): Either[DomainError, FrameSet] =
    FrameSet.join(
      p,
      framesEnvelope.streamIndex,
      framesEnvelope.geometry,
      framesEnvelope.tool,
      framesEnvelope.args,
      framesEnvelope.byteLength,
      framesEnvelope.framesSha256
    )
  private lazy val frames: FrameSet = right(frameSetOf(probe))
  private lazy val detectorEnvelope: DetectorEnvelope =
    right(DetectorEnvelope.parse(text("f0-v1.detector-envelope.json")))
  private lazy val request: DetectorRequest =
    right(DetectorRequest.parse(text(detectorEnvelope.requestFile)))
  private lazy val outcome: DetectorOutcome =
    right(
      DetectorOutcome.parse(
        right(detectorEnvelope.verifyOutcome(resource(detectorEnvelope.outcomeFile)))
      )
    )
  private lazy val result: BoundarySearchResult =
    right(BoundarySearch.join(frames, request, outcome, detectorEnvelope.worker))

  private def joinWith(
      req: DetectorRequest = request,
      out: DetectorOutcome = outcome,
      worker: ToolRealization = detectorEnvelope.worker
  ): Either[DomainError, BoundarySearchResult] = BoundarySearch.join(frames, req, out, worker)

  test(
    "the recorded outcome replays into proposals at the declared shot starts, through the packet index"
  ):
    assertEquals(result.authority, ObservationAuthority.Draft)
    assertEquals(frames.count, 45)
    assertEquals(frames.framesSha256, framesEnvelope.framesSha256)
    assertEquals(framesEnvelope.args, Ffmpeg.canonicalArgs(0))
    val declared = manifest.stream(0).flatMap(_.declaredFrames).get
    // Shots two and three begin at generated frames 16 and 32; after the declared drops those are
    // surviving ordinals 14 and 29, whose PTS the packet index gives as 16000 and 32000.
    val expectedInstants = Vector(16, 32).map(n =>
      declared.expectedPts.find(_ == n.toLong * declared.nominalTicksPerFrame).get
    )
    assertEquals(result.proposals.map(_.at.at), expectedInstants)
    assertNotEquals(
      result.proposals.map(_.at.at),
      Vector(14000L, 29000L),
      "ordinal x nominal ticks is not a PTS"
    )
    assertEquals(
      result.proposals.map(p => (p.window.start, p.window.endExclusive)),
      Vector((15000L, 16000L), (31000L, 32000L))
    )
    assertEquals(result.proposals.map(_.score), Vector(20.0, 20.0))
    assert(result.proposals.forall(_.layer == BoundaryLayer.Shot))
    assert(result.proposals.forall(_.at.axis == result.axis.id))
    assertEquals(result.noCandidate, None)
    assertEquals((result.examined.start, result.examined.endExclusive), (0L, 48000L))
    assertEquals(result.applied.kernelSize, 5)
    assertEquals(result.recipe.threshold, 15.0)
    assert(result.receipt.parameters.contains(BoundarySearch.EditListAssumption))

  test("replay is identical; another worker realization is another derivation"):
    val again = right(joinWith())
    assertEquals(again.identity, result.identity)
    val otherWorker = right(
      ToolRealization.of(
        "scenedetect-worker",
        detectorEnvelope.worker.versionLine,
        Checksum.ofText("other script")
      )
    )
    val under = right(joinWith(worker = otherWorker))
    assertNotEquals(under.receipt.identity, result.receipt.identity)
    assertNotEquals(under.identity, result.identity)
    assert(result.receipt.parameters.contains(request.recipe.identity.hex))
    assert(result.receipt.parameters.contains(detectorEnvelope.worker.identity.hex))
    assertEquals(result.receipt.inputChecksums, Vector(frames.framesSha256, frames.identity))

  test("an outcome claiming a runtime other than the worker it is joined under is refused"):
    val otherRuntime = outcome.copy(runtime = outcome.runtime.updated("scenedetect", "0.7.2"))
    refusedAt(joinWith(out = otherRuntime), "boundary/worker")
    val otherVersionLine = right(
      ToolRealization.of(
        "scenedetect-worker",
        "scenedetect 0.7.2 python 3.13.11",
        Checksum.ofText("x")
      )
    )
    refusedAt(joinWith(worker = otherVersionLine), "boundary/worker")
    assertEquals(outcome.runtimeLine, detectorEnvelope.worker.versionLine)

  test("what the library applied must satisfy the recipe; an echo alone is not enough"):
    refusedAt(
      joinWith(out = outcome.copy(applied = outcome.applied.copy(threshold = 27.0))),
      "boundary/applied"
    )
    refusedAt(
      joinWith(out = outcome.copy(applied = outcome.applied.copy(minSceneLen = 15))),
      "boundary/applied"
    )
    refusedAt(
      joinWith(out = outcome.copy(applied = outcome.applied.copy(deltaEdges = 1.0))),
      "boundary/applied"
    )
    refusedAt(
      joinWith(out = outcome.copy(applied = outcome.applied.copy(kernelSize = 0))),
      "boundary/applied"
    )
    val declaredKernel = request.recipe.copy(kernelSize = Some(5))
    assert(
      joinWith(
        req = request.copy(recipe = declaredKernel),
        out = outcome.copy(recipe = declaredKernel)
      ).isRight
    )
    val overriddenKernel = request.recipe.copy(kernelSize = Some(7))
    refusedAt(
      joinWith(
        req = request.copy(recipe = overriddenKernel),
        out = outcome.copy(recipe = overriddenKernel)
      ),
      "boundary/applied"
    )
    invalidFormatIn(
      DetectorOutcome.parse(
        right(detectorEnvelope.verifyOutcome(resource(detectorEnvelope.outcomeFile)))
          .replaceFirst("\"kernelSize\": 5", "\"kernelSize\": null")
      ),
      "applied.kernelSize"
    )

  private def invalidFormatIn(e: Either[DomainError, Any], kind: String): Unit =
    e match
      case Left(DomainError.InvalidFormat(k, _, _)) => assertEquals(k, kind)
      case Left(other) => fail(s"refused at the wrong place: ${other.message}")
      case Right(v)    => fail(s"expected InvalidFormat $kind, got $v")

  test("an outcome recorded under another recipe, or for another request, is refused"):
    refusedAt(
      joinWith(req = request.copy(recipe = request.recipe.copy(threshold = 27.0))),
      "boundary/recipe"
    )
    refusedAt(joinWith(out = outcome.copy(requestId = "someone-else")), "boundary/request-id")
    refusedAt(
      joinWith(req = request.copy(framesSha256 = Checksum.ofText("other frames"))),
      "boundary/request"
    )

  test("an outcome describing other frames, or a partial coverage, is refused"):
    refusedAt(
      joinWith(out = outcome.copy(framesSha256 = Checksum.ofText("other frames"))),
      "boundary/frames"
    )
    refusedAt(joinWith(out = outcome.copy(framesCount = 44)), "boundary/frames")
    refusedAt(joinWith(out = outcome.copy(pixelFormat = "rgb24")), "boundary/frames")
    refusedAt(joinWith(out = outcome.copy(coverageObserved = 44)), "boundary/coverage")

  test("a cut with no preceding frame, outside the frames, or repeated is refused"):
    refusedAt(joinWith(out = outcome.copy(cuts = Vector(0))), "boundary/cut")
    refusedAt(joinWith(out = outcome.copy(cuts = Vector(45))), "boundary/cut")
    refusedAt(joinWith(out = outcome.copy(cuts = Vector(14, 14))), "boundary/cut")
    refusedAt(joinWith(out = outcome.copy(cuts = Vector(29, 14))), "boundary/cut")

  test("empty detector output is examined-with-no-candidate and can never become a negative claim"):
    val none = right(joinWith(out = outcome.copy(cuts = Vector.empty)))
    assertEquals(none.proposals, Vector.empty)
    assertEquals(none.noCandidate, Some(BoundarySearchCoverage.ExaminedNoCandidate))
    assert(BoundarySearchCoverage.negativeFromEmptyDetector(BoundaryLayer.Shot).isLeft)
    assertNotEquals(none.identity, result.identity)

  test("metrics must be one row per frame, and a cut must carry a score"):
    refusedAt(
      joinWith(out = outcome.copy(metrics = outcome.metrics.dropRight(1))),
      "boundary/metrics"
    )
    val scoreless =
      outcome.metrics.map(m => if m.ordinal == 14 then m.copy(values = Map.empty) else m)
    refusedAt(joinWith(out = outcome.copy(metrics = scoreless)), "boundary/score")

  test(
    "a picture stream whose reported start is not its first presented packet is refused: no edit list is assumed away"
  ):
    val shifted = output.copy(streams =
      output.streams.map(s => if s.index == 0 then s.copy(startPts = Some(1000L)) else s)
    )
    val shiftedProbe = right(
      MediaProbe.join(
        manifest,
        right(manifest.verify(bytes)),
        probeEnvelope.tool,
        probeEnvelope.args,
        shifted
      )
    )
    val shiftedFrames = right(frameSetOf(shiftedProbe))
    refusedAt(
      BoundarySearch.join(shiftedFrames, request, outcome, detectorEnvelope.worker),
      "boundary/edit-list"
    )
    val discardMiddle = output.copy(packets =
      output.packets.map(p =>
        if p.streamIndex == 0 && p.pts.contains(2000L) then
          p.copy(flags = right(PacketFlags.parse("KD_")))
        else p
      )
    )
    val discardProbe = right(
      MediaProbe.join(
        manifest,
        right(manifest.verify(bytes)),
        probeEnvelope.tool,
        probeEnvelope.args,
        discardMiddle
      )
    )
    // With a middle packet discarded the recorded decode (45 frames) no longer describes the
    // stream, which refuses first; a decode consistent with the 44 presented packets reaches the
    // edit-list guard, whose discard clause is the one a real picture edit list would trip.
    refusedAt(frameSetOf(discardProbe), "frames/bytes")
    val frames44 = right(
      FrameSet.join(
        discardProbe,
        0,
        framesEnvelope.geometry,
        framesEnvelope.tool,
        framesEnvelope.args,
        framesEnvelope.geometry.frameBytes * 44L,
        Checksum.ofText("a decode of the 44 presented frames")
      )
    )
    assertEquals(frames44.index.discarded, 1)
    refusedAt(
      BoundarySearch.join(frames44, request, outcome, detectorEnvelope.worker),
      "boundary/edit-list"
    )

  test(
    "a double that reaches an identity is finite and canonical: equal recipes have one identity"
  ):
    // Measured 2026-09-02: `-0.0` is reachable from JSON, `-0.0 == 0.0` is true, and the two render
    // differently, so an identity built from the raw value would give two identities for one value.
    val negativeZero = request.recipe.copy(deltaEdges = -0.0)
    assertEquals(negativeZero, request.recipe, "the two recipes are equal")
    assertEquals(negativeZero.identity, request.recipe.identity, "so they must have one identity")
    // A JSON literal outside the double range parses to an infinity; the wire reader refuses it.
    val requestText = text(detectorEnvelope.requestFile)
    val infinite = requestText.replaceFirst("\"threshold\": 15.0", "\"threshold\": 1e400")
    assert(infinite != requestText)
    DetectorRequest.parse(infinite) match
      case Left(DomainError.InvalidFormat(k, _, r)) =>
        assertEquals(k, "detector.threshold")
        assert(r.contains("finite"), r)
      case other => fail(s"expected a finite-number refusal, got $other")
    // A non-finite score cannot reach a proposal identity either.
    val infScore = outcome.copy(metrics =
      outcome.metrics.map(m =>
        if m.ordinal == 14 then
          m.copy(values = m.values.updated(DetectorOutcome.ScoreKey, Double.PositiveInfinity))
        else m
      )
    )
    refusedAt(joinWith(out = infScore), "boundary/score")

  test(
    "a localization proposal carries no morphology and binds its recipe; a claim needs a morphology supplied separately"
  ):
    val proposal = result.proposals.head
    val claim = right(BoundaryClaim.shot(proposal.id, ShotMorphology.hardCut(proposal.at)))
    assert(claim.morphology.isDefined)
    assertEquals(claim.instant.map(_.at), Some(proposal.at.at))
    assertEquals(proposal.recipe, request.recipe.identity)
    assertEquals(result.proposals.map(_.id).distinct.size, 2)
    assertNotEquals(
      proposal.id.value,
      ContentAddress.of("shot-boundary", "f0-v1", proposal.at.at.toString),
      "a recipe-blind id would let two recipes share one proposal"
    )

  test(
    "a frame set is one decoded frame per presented packet of a Decoded picture stream at the declared geometry"
  ):
    refusedAt(
      FrameSet.join(
        probe,
        0,
        framesEnvelope.geometry,
        framesEnvelope.tool,
        framesEnvelope.args,
        framesEnvelope.byteLength - 1728L,
        framesEnvelope.framesSha256
      ),
      "frames/bytes"
    )
    refusedAt(
      FrameSet.join(
        probe,
        1,
        framesEnvelope.geometry,
        framesEnvelope.tool,
        framesEnvelope.args,
        framesEnvelope.byteLength,
        framesEnvelope.framesSha256
      ),
      "frames/stream"
    )
    refusedAt(
      FrameSet.join(
        probe,
        0,
        PictureGeometry(64, 36),
        framesEnvelope.tool,
        framesEnvelope.args,
        64L * 36L * 3L * 45L,
        framesEnvelope.framesSha256
      ),
      "frames/geometry"
    )
    val indexedOnly = right(
      FixtureManifest.parse(
        text("f0-v1.manifest.json")
          .replaceFirst("\"disposition\": \"Decoded\"", "\"disposition\": \"PacketIndexedOnly\"")
      )
    )
    val probeIndexedOnly = right(
      MediaProbe.join(
        indexedOnly,
        right(indexedOnly.verify(bytes)),
        probeEnvelope.tool,
        probeEnvelope.args,
        output
      )
    )
    refusedAt(frameSetOf(probeIndexedOnly), "frames/disposition")
    val otherTool =
      right(ToolRealization.of("ffmpeg", "ffmpeg version 9.0.1", Checksum.ofText("other")))
    val under = right(
      FrameSet.join(
        probe,
        0,
        framesEnvelope.geometry,
        otherTool,
        framesEnvelope.args,
        framesEnvelope.byteLength,
        framesEnvelope.framesSha256
      )
    )
    assertNotEquals(under.identity, frames.identity)
    // A decoder that fails refuses on its exit code; `sh` given ffmpeg's arguments exits non-zero.
    refusedAt(
      Ffmpeg.extract(Path.of("/bin/sh"), Path.of("/dev/null"), 0, Path.of("/dev/null")),
      "ffmpeg/exit"
    )

  test("live ffmpeg and worker, when this machine has both, reproduce the recorded proposals"):
    val ffmpeg = Ffmpeg.locate()
    val python = BoundarySearchSuite.workerPython
    val script = BoundarySearchSuite.workerScript
    assume(ffmpeg.isDefined, "no ffmpeg on PATH; live court not run")
    assume(
      python.isDefined && script.isDefined,
      "worker environment not installed; live court not run (nothing is installed)"
    )
    val dir = Files.createTempDirectory("f0-boundary-")
    try
      val input = dir.resolve("f0-v1.mov")
      Files.write(input, bytes)
      val raw = dir.resolve("f0-v1.frames.bgr")
      val tool = right(ToolRealization.observe(Ffmpeg.ToolName, ffmpeg.get))
      right(Ffmpeg.extract(ffmpeg.get, input, 0, raw))
      val decoded = Files.readAllBytes(raw)
      val live = right(
        FrameSet.join(
          probe,
          0,
          framesEnvelope.geometry,
          tool,
          Ffmpeg.canonicalArgs(0),
          decoded.length.toLong,
          Checksum.ofBytes(decoded)
        )
      )
      // The same decoder build must decode to the recorded bytes; another build may not, and then
      // the identities differ by construction.
      if tool == framesEnvelope.tool then
        assertEquals(live.framesSha256, framesEnvelope.framesSha256)
      val issued = DetectorRequest.issue(live, request.recipe, request.requestId)
      val reqPath = dir.resolve("request.json")
      val outPath = dir.resolve("outcome.json")
      Files.writeString(reqPath, issued.render(raw.toString))
      val run = right(
        Subprocess.run(
          Vector(python.get.toString, script.get.toString, reqPath.toString, outPath.toString)
        )
      )
      assertEquals(run.exitCode, 0, run.stderr)
      val liveOutcome = right(DetectorOutcome.parse(Files.readString(outPath)))
      // The worker realization is observed independently of the outcome (the interpreter is asked
      // for its versions and the script is hashed), so the join's runtime check compares two sources.
      val worker = right(WorkerRealization.observe(python.get, script.get))
      val liveResult = right(BoundarySearch.join(live, issued, liveOutcome, worker))
      assertEquals(liveResult.proposals.map(_.at.at), result.proposals.map(_.at.at))
      assertEquals(liveResult.proposals.map(_.score), result.proposals.map(_.score))
      if worker == detectorEnvelope.worker && live.framesSha256 == frames.framesSha256 then
        assertEquals(liveOutcome, outcome)
    finally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => { Files.deleteIfExists(p); () })
      ()

object BoundarySearchSuite:
  private def existing(candidates: Vector[Path]): Option[Path] =
    candidates.find(Files.isRegularFile(_))

  /** The locally installed worker interpreter, if present. Never installed by the court. */
  def workerPython: Option[Path] =
    Option(System.getenv("STORYMODEL4S_SCENEDETECT_WORKER_PYTHON"))
      .map(Path.of(_))
      .filter(Files.isRegularFile(_))
      .orElse(
        existing(
          Vector(Path.of("media/worker/.venv/bin/python"), Path.of("worker/.venv/bin/python"))
        )
      )

  def workerScript: Option[Path] =
    existing(
      Vector(Path.of("media/worker/scenedetect_worker.py"), Path.of("worker/scenedetect_worker.py"))
    )
